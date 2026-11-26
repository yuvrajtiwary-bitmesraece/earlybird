package io.github.yuvrajtiwary.earlybird.events;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.common.Html;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.SmartLifecycle;

/**
 * Keeps an eye on Kafka for the rest of the app (spec 3.2, 3.11 and section 8):
 * <ul>
 *   <li>right after startup, checks that the five topics exist (topics are never auto-created) and logs a clear
 *       error naming any that don't;
 *   <li>every 30 s, checks that the cluster answers. While it doesn't, health is {@code DEGRADED}, not
 *       {@code DOWN}: the app keeps working, events wait in Postgres, and the outbox relay publishes them later.
 *       Meanwhile the bus fails publishes at once instead of waiting 10 s for each;
 *   <li>after 5 minutes unreachable, tells the admin on Telegram;
 *   <li>gives {@code /admin} a status line and the number of dead letters.
 * </ul>
 */
public class KafkaMonitor implements SmartLifecycle {

    /** What the monitor asks Kafka. An interface, so the logic is tested without a broker. */
    public interface Cluster {

        /** Partition counts of those {@code topics} that exist. Throws if Kafka doesn't answer in time. */
        Map<String, Integer> partitions(Collection<String> topics, Duration timeout) throws Exception;

        /** Messages currently kept in a topic: latest minus earliest offset, over all partitions. */
        long retained(String topic, Duration timeout) throws Exception;
    }

    public static final Status DEGRADED = new Status("DEGRADED");
    static final Duration INTERVAL = Duration.ofSeconds(30);
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final Duration ALERT_AFTER = Duration.ofMinutes(5);
    static final int PARTITIONS = 2;

    private static final Logger log = LoggerFactory.getLogger(KafkaMonitor.class);
    private static final DateTimeFormatter IST = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final Cluster cluster;
    private final List<String> topics;
    private final String deadLetterTopic;
    private final AdminAlerts admin;
    private final Clock clock;
    private final List<Runnable> whenReachable = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService timer;

    private volatile boolean checked;
    private volatile Instant unreachableSince;
    private volatile String lastError;
    private volatile boolean outageReported;
    private volatile List<String> missingTopics = List.of();
    private volatile Map<String, Integer> wrongPartitions = Map.of();

    public KafkaMonitor(Cluster cluster, String topicPrefix, AdminAlerts admin, Clock clock) {
        this.cluster = cluster;
        this.topics = Arrays.stream(Topic.values()).map(t -> t.topicName(topicPrefix)).toList();
        this.deadLetterTopic = Topic.DLQ.topicName(topicPrefix);
        this.admin = admin;
        this.clock = clock;
    }

    /**
     * Runs {@code action} after every successful check, on the monitor's thread. The bus uses it to start
     * consumers that couldn't be created while Kafka was unreachable.
     */
    public void whenReachable(Runnable action) {
        whenReachable.add(action);
    }

    /** One check. Runs every 30 s on the monitor's thread; tests call it directly. */
    public synchronized void probe() {
        Map<String, Integer> found;
        try {
            found = cluster.partitions(topics, TIMEOUT);
        } catch (Exception e) {
            unreachable(e);
            return;
        }
        Instant now = clock.instant();
        if (unreachableSince != null) {
            Duration down = Duration.between(unreachableSince, now);
            log.info("Kafka is reachable again after {} s", down.toSeconds());
            if (outageReported) {
                admin.notifyOncePerDay("kafka-back", "Kafka is reachable again after " + minutes(down)
                        + ". The outbox relay publishes what waited at the next tick.");
            }
        }
        List<String> missing = topics.stream().filter(t -> !found.containsKey(t)).toList();
        if (!missing.isEmpty() && !missing.equals(missingTopics)) {
            log.error("Kafka topics missing: {}. Create them in Aiven with {} partitions each (topics are never"
                    + " auto-created); until then, events for them can't be published.", missing, PARTITIONS);
        }
        Map<String, Integer> wrong = new TreeMap<>();
        found.forEach((topic, partitions) -> {
            if (partitions != PARTITIONS) {
                wrong.put(topic, partitions);
            }
        });
        if (!wrong.isEmpty() && !wrong.equals(wrongPartitions)) {
            log.warn("Kafka topics with other than {} partitions: {}. It works, but differs from the design.",
                    PARTITIONS, wrong);
        }
        if (!checked && missing.isEmpty()) {
            log.info("Kafka OK: all {} topics exist", topics.size());
        }
        missingTopics = missing;
        wrongPartitions = Map.copyOf(wrong);
        unreachableSince = null;
        lastError = null;
        outageReported = false;
        checked = true;
        for (Runnable action : whenReachable) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("After the Kafka check: {}", e.toString());
            }
        }
    }

    private void unreachable(Exception e) {
        Instant now = clock.instant();
        lastError = describe(e);
        if (unreachableSince == null) {
            unreachableSince = now;
            log.warn("Kafka unreachable ({}). Events wait in Postgres; the outbox relay publishes them when it's back.",
                    lastError);
        }
        checked = true;
        Duration down = Duration.between(unreachableSince, now);
        if (!outageReported && down.compareTo(ALERT_AFTER) >= 0) {
            outageReported = true;
            admin.notifyOncePerDay("kafka-down", "Kafka has been unreachable for " + minutes(down) + " (since "
                    + IST.format(unreachableSince) + " IST): <code>" + Html.escape(lastError) + "</code>\n"
                    + "New postings and alerts wait in Postgres and go out when it's back. If Aiven powered the"
                    + " service off, power it on in the Aiven console.");
        }
    }

    /** True while the last check failed: publishing would only wait for a timeout. */
    public boolean unreachable() {
        return unreachableSince != null;
    }

    public Health health() {
        if (!checked) {
            return Health.unknown().withDetail("kafka", "not checked yet").build();
        }
        if (unreachableSince != null) {
            return Health.status(DEGRADED).withDetail("unreachableSince", unreachableSince.toString())
                    .withDetail("error", lastError).build();
        }
        if (!missingTopics.isEmpty()) {
            return Health.status(DEGRADED).withDetail("missingTopics", missingTopics).build();
        }
        return Health.up().withDetail("topics", topics.size()).build();
    }

    /** One line for {@code /admin}, already HTML-escaped. */
    public String summary() {
        if (!checked) {
            return "not checked yet";
        }
        if (unreachableSince != null) {
            return "unreachable since " + IST.format(unreachableSince) + " IST";
        }
        if (!missingTopics.isEmpty()) {
            return "topics missing: " + Html.escape(String.join(", ", missingTopics));
        }
        return "OK";
    }

    /** Dead letters still kept in {@code eb.dlq.v1}, or empty if Kafka can't say right now. */
    public OptionalLong deadLetters() {
        if (unreachable() || missingTopics.contains(deadLetterTopic)) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(cluster.retained(deadLetterTopic, TIMEOUT));
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    private static String minutes(Duration d) {
        long m = Math.max(1, d.toMinutes());
        return m + (m == 1 ? " minute" : " minutes");
    }

    private static String describe(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    @Override
    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("kafka-monitor").factory());
        // The first check runs right away but on this thread, so a Kafka outage never delays startup.
        timer.scheduleWithFixedDelay(this::probeQuietly, 0, INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    private void probeQuietly() {
        try {
            probe();
        } catch (RuntimeException e) {
            log.error("Kafka check failed", e); // keep the schedule alive
        }
    }

    @Override
    public void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
    }

    @Override
    public boolean isRunning() {
        return timer != null;
    }

    // ----------------------------------------------------------------------------------- the real cluster

    /**
     * {@link Cluster} through Kafka's admin client, created on first use and retried on every check until it
     * exists: Kafka clients refuse to be created while the bootstrap host doesn't resolve (for example when
     * Aiven has powered the service off), and that must not stop the app from starting.
     */
    public static final class AdminCluster implements Cluster, AutoCloseable {

        private final Map<String, Object> config;
        private Admin admin;

        public AdminCluster(Map<String, Object> config) {
            this.config = Map.copyOf(config);
        }

        private synchronized Admin admin() {
            if (admin == null) {
                admin = Admin.create(config);
            }
            return admin;
        }

        @Override
        public synchronized void close() {
            if (admin != null) {
                admin.close(Duration.ofSeconds(5));
                admin = null;
            }
        }

        @Override
        public Map<String, Integer> partitions(Collection<String> wanted, Duration timeout) throws Exception {
            int ms = (int) timeout.toMillis();
            Admin admin = admin();
            Set<String> existing = admin.listTopics(new ListTopicsOptions().timeoutMs(ms)).names()
                    .get(ms, TimeUnit.MILLISECONDS);
            List<String> present = wanted.stream().filter(existing::contains).toList();
            if (present.isEmpty()) {
                return Map.of();
            }
            Map<String, TopicDescription> described = admin.describeTopics(present,
                    new DescribeTopicsOptions().timeoutMs(ms)).allTopicNames().get(ms, TimeUnit.MILLISECONDS);
            Map<String, Integer> partitions = new HashMap<>();
            described.forEach((name, d) -> partitions.put(name, d.partitions().size()));
            return partitions;
        }

        @Override
        public long retained(String topic, Duration timeout) throws Exception {
            int ms = (int) timeout.toMillis();
            Admin admin = admin();
            TopicDescription d = admin.describeTopics(List.of(topic), new DescribeTopicsOptions().timeoutMs(ms))
                    .allTopicNames().get(ms, TimeUnit.MILLISECONDS).get(topic);
            Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            d.partitions().forEach(p -> {
                earliest.put(new TopicPartition(topic, p.partition()), OffsetSpec.earliest());
                latest.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest());
            });
            var options = new ListOffsetsOptions().timeoutMs(ms);
            var first = admin.listOffsets(earliest, options).all().get(ms, TimeUnit.MILLISECONDS);
            var last = admin.listOffsets(latest, options).all().get(ms, TimeUnit.MILLISECONDS);
            long total = 0;
            for (var entry : last.entrySet()) {
                total += entry.getValue().offset() - first.get(entry.getKey()).offset();
            }
            return total;
        }
    }
}
