package io.github.yuvrajtiwary.earlybird.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import io.github.yuvrajtiwary.earlybird.support.QStashTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec Block 6 "done when": unsigned → 401, signed → 202, overlapping runs skipped. Plus retention. */
@IntegrationTest
class InternalEndpointsIT {

    /** application-test.yml's PUBLIC_BASE_URL: the URL QStash signs is the public one, not localhost. */
    private static final String PUBLIC = "https://earlybird.test";

    @Autowired DistributedLock lock;
    @Autowired JdbcClient jdbc;
    @Value("${local.server.port}") int port;

    private HttpResponse<String> post(String job, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/" + job))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (signature != null) {
            request.header("Upstash-Signature", signature);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String signed(String job) {
        return QStashTokens.sign(QStashTokens.CURRENT_KEY, PUBLIC + "/internal/" + job, "", Instant.now());
    }

    @Test
    void unsignedOrForgedCallsGet401() throws Exception {
        assertThat(post("tick", null).statusCode()).isEqualTo(401);
        String forged = QStashTokens.sign("sig_not_ours", PUBLIC + "/internal/tick", "", Instant.now());
        assertThat(post("tick", forged).statusCode()).isEqualTo(401);
        // A real token, but for another endpoint.
        assertThat(post("tick", signed("digest")).statusCode()).isEqualTo(401);
    }

    @Test
    void onlyPostAndOnlyKnownJobs() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var get = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/tick")).GET()
                    .build(), HttpResponse.BodyHandlers.discarding());
            assertThat(get.statusCode()).isEqualTo(405);
        }
        assertThat(post("reboot", signed("reboot")).statusCode()).isEqualTo(404);
    }

    /** Overlapping calls (QStash retrying, or a slow tick) are answered 202 "skipped" and do nothing. */
    @Test
    void aTickThatIsStillRunningMakesTheNextOneSkip() throws Exception {
        try (var held = lock.tryAcquire("tick", Duration.ofMinutes(1)).orElseThrow()) {
            var response = post("tick", signed("tick"));
            assertThat(response.statusCode()).isEqualTo(202);
            assertThat(response.body()).contains("skipped");
        }
    }

    /** Signed → 202 at once, the work happens in the background: here the nightly retention cleanup. */
    @Test
    void aSignedMaintenanceCallIsAcceptedAndCleansUp() throws Exception {
        UUID oldEvent = UUID.randomUUID();
        UUID newEvent = UUID.randomUUID();
        OffsetDateTime tenDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
        jdbc.sql("insert into processed_events (event_id, consumer, processed_at) values (:e, 'it', :t)")
                .param("e", oldEvent).param("t", tenDaysAgo).update();
        jdbc.sql("insert into processed_events (event_id, consumer) values (:e, 'it')").param("e", newEvent).update();
        long oldUpdate = 900_000_000L + (long) (Math.random() * 1_000_000);
        jdbc.sql("insert into telegram_updates (update_id, received_at) values (:u, :t)")
                .param("u", oldUpdate).param("t", tenDaysAgo).update();

        var response = post("maintenance", signed("maintenance"));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("started");
        await().atMost(Duration.ofSeconds(20)).until(() -> count("processed_events where event_id = :e", oldEvent) == 0);
        assertThat(count("processed_events where event_id = :e", newEvent)).isEqualTo(1);
        assertThat(count("telegram_updates where update_id = :e", oldUpdate)).isZero();
    }

    /** The database lock: one holder at a time, free again on release. */
    @Test
    void theDatabaseLockIsExclusive() {
        var postgres = (lock instanceof PostgresAdvisoryLock p) ? p : null;
        assertThat(postgres).as("no Redis in tests, so the Postgres lock is used").isNotNull();
        var first = lock.tryAcquire("it-lock", Duration.ofMinutes(1));
        assertThat(first).isPresent();
        assertThat(lock.tryAcquire("it-lock", Duration.ofMinutes(1))).isEmpty();
        assertThat(lock.tryAcquire("it-other", Duration.ofMinutes(1))).hasValueSatisfying(DistributedLock.Handle::close);
        first.get().close();
        lock.tryAcquire("it-lock", Duration.ofMinutes(1)).orElseThrow().close();
    }

    private int count(String fromWhere, Object param) {
        return jdbc.sql("select count(*) from " + fromWhere).param("e", param).query(Integer.class).single();
    }
}
