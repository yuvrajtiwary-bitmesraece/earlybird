package io.github.yuvrajtiwary.earlybird.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

/**
 * The production event bus ({@code EVENT_BUS=kafka}), spec section 3.2.
 *
 * <p><b>Publishing:</b> the envelope as JSON, keyed so that one key's events land on one partition and stay in
 * order. The returned future completes only when Kafka has the event ({@code acks=all}); callers mark their row
 * published only then. While the {@link KafkaMonitor} knows Kafka is unreachable, publishing fails at once.
 *
 * <p><b>Consuming:</b> one listener container per consumer group, which reads every partition of the group's
 * topics. Each event is handled synchronously, and its offset is committed only after the handler returns.
 * Handlers commit their own database transaction before returning, so a committed offset always means the
 * work is saved. A failing event is retried after 1 s, 5 s and 30 s, then dead-lettered; a poison event (or a
 * {@link DeadLetterException}) is dead-lettered at once.
 *
 * <p>Consumer groups are the spec's ({@code eb-matcher} and so on) in production. Other environments add their
 * name ({@code dev.eb-matcher}): Aiven's free plan allows one Kafka service, which dev and prod share, and a
 * laptop in the production groups would take partitions, and with them events, away from production.
 */
public final class KafkaEventBus implements EventBus, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);
    /** Spec 3.2: keep each message under 8 KB before compression. Bigger ones still go (Kafka takes 1 MB). */
    static final int MAX_EVENT_BYTES = 8 * 1024;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private record Subscription<T>(Topic topic, Class<T> payloadType, EventHandler<T> handler) {}

    /** A group whose consumer couldn't be created yet; started when the monitor next reaches Kafka. */
    private record Pending(String consumerGroup, Map<String, Subscription<?>> topics) {}

    private final KafkaTemplate<String, byte[]> template;
    private final ConsumerFactory<String, byte[]> consumers;
    private final EventJson json;
    private final KafkaMonitor monitor;
    private final String topicPrefix;
    private final String env;
    private final List<Duration> retryBackoff;
    private final Clock clock;
    private final MeterRegistry meters;
    /** consumer group → (topic name → subscription), in subscription order. */
    private final Map<String, Map<String, Subscription<?>>> groups = new LinkedHashMap<>();
    private final List<KafkaMessageListenerContainer<String, byte[]>> containers = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new ArrayList<>();
    private volatile boolean running;

    public KafkaEventBus(KafkaTemplate<String, byte[]> template, ConsumerFactory<String, byte[]> consumers,
            EventJson json, KafkaMonitor monitor, String topicPrefix, String env, List<Duration> retryBackoff,
            Clock clock, MeterRegistry meters) {
        this.template = template;
        this.consumers = consumers;
        this.json = json;
        this.monitor = monitor;
        this.topicPrefix = topicPrefix;
        this.env = env;
        this.retryBackoff = List.copyOf(retryBackoff);
        this.clock = clock;
        this.meters = meters;
        monitor.whenReachable(this::startPending);
    }

    @Override
    public CompletableFuture<Void> publish(Topic topic, String key, EventEnvelope<?> event) {
        if (monitor.unreachable()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Kafka is unreachable; the outbox relay publishes this later"));
        }
        try {
            byte[] value = json.write(event);
            if (value.length > MAX_EVENT_BYTES) {
                log.warn("{} {} is {} bytes, over the {}-byte guideline", event.type(), event.eventId(),
                        value.length, MAX_EVENT_BYTES);
            }
            return template.send(topic.topicName(topicPrefix), key, value).thenApply(sent -> (Void) null);
        } catch (RuntimeException e) {
            // KafkaTemplate throws (rather than failing the future) when metadata doesn't arrive in time.
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized <T> void subscribe(Topic topic, String consumerGroup, Class<T> payloadType,
            EventHandler<T> handler) {
        Map<String, Subscription<?>> topics = groups.computeIfAbsent(consumerGroup, g -> new LinkedHashMap<>());
        String name = topic.topicName(topicPrefix);
        if (topics.containsKey(name)) {
            throw new IllegalStateException(consumerGroup + " is already subscribed to " + topic);
        }
        var subscription = new Subscription<>(topic, payloadType, handler);
        topics.put(name, subscription);
        if (running) {
            // Modules subscribe while the context starts, before this bus starts. A later subscriber (tests) gets
            // its own container: the same group id, just this topic.
            startContainer(consumerGroup, Map.of(name, subscription));
        }
    }

    /** The group id on the broker: the spec's name in production, prefixed with the environment elsewhere. */
    String groupId(String consumerGroup) {
        return "prod".equals(env) ? consumerGroup : env + "." + consumerGroup;
    }

    private void startContainer(String consumerGroup, Map<String, Subscription<?>> topics) {
        var properties = new ContainerProperties(topics.keySet().toArray(String[]::new));
        properties.setGroupId(groupId(consumerGroup));
        properties.setClientId("earlybird-" + env + "-" + consumerGroup);
        properties.setAckMode(ContainerProperties.AckMode.RECORD); // commit each event once its handler returned
        properties.setDeliveryAttemptHeader(true);                   // tells the dead letter how many attempts
        properties.setShutdownTimeout(SHUTDOWN_TIMEOUT.toMillis());
        properties.setMessageListener((MessageListener<String, byte[]>) record -> dispatch(topics, record));

        var errors = new DefaultErrorHandler(new DeadLetterRecoverer(template, json, Topic.DLQ.topicName(topicPrefix),
                consumerGroup, clock, meters), new RetryBackOff(retryBackoff));
        errors.addNotRetryableExceptions(DeadLetterException.class);

        var container = new KafkaMessageListenerContainer<>(consumers, properties);
        container.setCommonErrorHandler(errors);
        container.setBeanName("kafka-" + consumerGroup);
        try {
            container.start();
        } catch (RuntimeException e) {
            // A Kafka client can't even be created while the bootstrap host doesn't resolve (Aiven powered the
            // service off). Not fatal: the app runs on, and this group starts once Kafka answers again.
            log.warn("Couldn't start consuming {} as {} ({}); will retry when Kafka is reachable",
                    topics.keySet(), groupId(consumerGroup), rootCause(e));
            pending.add(new Pending(consumerGroup, topics));
            return;
        }
        containers.add(container);
        log.info("Consuming {} as {}", topics.keySet(), groupId(consumerGroup));
    }

    /** Called after each successful Kafka check: starts the groups that couldn't start before. */
    synchronized void startPending() {
        if (!running || pending.isEmpty()) {
            return;
        }
        List<Pending> retry = new ArrayList<>(pending);
        pending.clear();
        retry.forEach(p -> startContainer(p.consumerGroup(), p.topics()));
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }

    private void dispatch(Map<String, Subscription<?>> topics, ConsumerRecord<String, byte[]> record) {
        Subscription<?> subscription = topics.get(record.topic());
        if (subscription != null) {
            deliver(subscription, record);
        }
    }

    private <T> void deliver(Subscription<T> subscription, ConsumerRecord<String, byte[]> record) {
        EventEnvelope<T> event = json.read(record.value(), subscription.payloadType());
        try {
            subscription.handler().handle(event);
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while handling " + event.type() + " " + event.eventId(), e);
        } catch (Exception e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    // ------------------------------------------------------------------------------------------- lifecycle

    @Override
    public synchronized void start() {
        groups.forEach((group, topics) -> startContainer(group, new LinkedHashMap<>(topics)));
        running = true;
    }

    /**
     * Stops consuming. Every container finishes the event in hand first (up to 10 s), all at the same time, so
     * shutdown stays inside Boot's 20-second phase however many groups there are.
     */
    @Override
    public synchronized void stop() {
        running = false;
        pending.clear();
        List<KafkaMessageListenerContainer<String, byte[]>> all = new ArrayList<>(containers);
        containers.clear();
        var stopped = new CountDownLatch(all.size());
        all.forEach(container -> container.stop(stopped::countDown));
        try {
            if (!stopped.await(SHUTDOWN_TIMEOUT.toSeconds() + 2, TimeUnit.SECONDS)) {
                log.warn("Kafka consumers didn't stop within {} s", SHUTDOWN_TIMEOUT.toSeconds() + 2);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
