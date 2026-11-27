package io.github.yuvrajtiwary.earlybird.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository.Count;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.persistence.User;
import io.github.yuvrajtiwary.earlybird.persistence.UserRepository;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Block 7 "done when": the pages render and the numbers match the database. The statistics are checked against
 * numbers worked out by hand, on data placed in a window of time no other test writes to.
 */
@IntegrationTest
class WebIT {

    private static final AtomicLong IDS = new AtomicLong(7_000_000);

    @Autowired PublicStatsRepository stats;
    @Autowired PublicData data;
    @Autowired BoardRepository boards;
    @Autowired PostingRepository postings;
    @Autowired UserRepository users;
    @Autowired JdbcClient jdbc;
    @Value("${local.server.port}") int port;

    /** A posting first seen at {@code seen}; {@code published} may be null (as on Lever). */
    private UUID posting(Board board, String company, String title, Instant seen, Instant published) {
        Posting p = new Posting(Ids.newId(), board.id(), Source.GREENHOUSE, UUID.randomUUID().toString(),
                "https://jobs.example.com/" + UUID.randomUUID(), title, company, "Bengaluru", List.of(), published,
                seen, seen, 0, null, UUID.randomUUID().toString(), null, false, seen, false);
        assertThat(postings.insertIfAbsent(p)).isTrue();
        return p.id();
    }

    private void alert(User user, UUID posting, String mode, Instant created, Instant sent, String feedback) {
        jdbc.sql("""
                        insert into alerts (id, user_id, posting_id, score, matched_skills, missing_skills, mode, status,
                            created_at, sent_at, feedback, feedback_at)
                        values (:id, :u, :p, 80, '{}', '{}', :mode, :status, :created, :sent, :fb, :fbAt)
                        """)
                .param("id", Ids.newId()).param("u", user.id()).param("p", posting).param("mode", mode)
                .param("status", sent == null ? "queued" : "sent").param("created", utc(created))
                .param("sent", sent == null ? null : utc(sent)).param("fb", feedback)
                .param("fbAt", feedback == null ? null : utc(sent)).update();
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private Board newBoard() {
        String token = "web-" + UUID.randomUUID().toString().substring(0, 8);
        boards.insertIfAbsent(Source.GREENHOUSE, token, "Web " + token);
        return boards.find(Source.GREENHOUSE, token).orElseThrow();
    }

    private User newUser() {
        long tg = IDS.incrementAndGet();
        return users.create(tg, tg, "Web", null, Instant.now());
    }

    @Test
    void theStatisticsMatchHandWorkedNumbers() {
        // A day 40 days ago: nothing else in the test database is there.
        Instant t0 = Instant.parse("2026-10-18T06:00:00Z");
        Instant from = t0.minus(Duration.ofHours(1));
        Instant until = t0.plus(Duration.ofHours(6));
        Board board = newBoard();
        User user = newUser();

        // Four timely instant alerts, 10/20/30/40 s after their postings were found.
        // percentile_cont: p50 = 25 s; p95 = 30 + 0.85 × 10 = 38.5 s.
        int[] lags = {10, 20, 30, 40};
        for (int i = 0; i < lags.length; i++) {
            Instant seen = t0.plusSeconds(60L * i);
            // The first two come from Greenhouse with a publish time 100 s before we found them: 110 s and 120 s.
            Instant published = i < 2 ? seen.minusSeconds(100) : null;
            UUID p = posting(board, i % 2 == 0 ? "Acme" : "Beta", "Engineer " + i, seen, published);
            alert(user, p, "instant", seen.plusSeconds(1), seen.plusSeconds(lags[i]), i == 3 ? "not_relevant" : "relevant");
        }
        // Not counted for latency: a "recent match" sent 2 h after the posting was found, and a digest alert.
        UUID old = posting(board, "Acme", "Old job", t0, null);
        alert(user, old, "instant", t0.plus(Duration.ofHours(2)), t0.plus(Duration.ofHours(2)).plusSeconds(5), null);
        UUID digest = posting(board, "Gamma", "Digest job", t0, null);
        alert(user, digest, "digest", t0.plusSeconds(1), t0.plus(Duration.ofHours(3)), null);

        var delivery = stats.deliveryLag(from, until);
        assertThat(delivery.count()).isEqualTo(4);
        assertThat(delivery.p50()).isCloseTo(25.0, within(0.001));
        assertThat(delivery.p95()).isCloseTo(38.5, within(0.001));

        var published = stats.publishedToAlert(from, until);
        assertThat(published.count()).isEqualTo(2);
        assertThat(published.p50()).isCloseTo(115.0, within(0.001));
        assertThat(published.p95()).isCloseTo(119.5, within(0.001));

        var relevance = stats.relevance(from, until);
        assertThat(relevance.relevant()).isEqualTo(3);
        assertThat(relevance.notRelevant()).isEqualTo(1);

        assertThat(stats.topCompanies(from, until, 10)).containsExactly(
                new Count("Acme", 3), new Count("Beta", 2), new Count("Gamma", 1));
        assertThat(stats.postingsPerDay(from)).containsEntry(LocalDate.of(2026, 10, 18), 6);
        assertThat(stats.alertsPerDay(from)).containsEntry(LocalDate.of(2026, 10, 18), 6);

        // An empty window has no percentiles, not zeros.
        var none = stats.deliveryLag(from.minus(Duration.ofDays(1)), from);
        assertThat(none.count()).isZero();
        assertThat(none.p50()).isNull();
    }

    @Test
    void pagesRenderAndEscapePostingText() throws Exception {
        Board board = newBoard();
        String title = "<script>alert(1)</script> Backend Engineer " + UUID.randomUUID().toString().substring(0, 6);
        UUID id = posting(board, "Evil & Co", title, Instant.now(), null);
        jdbc.sql("""
                        insert into posting_tags (posting_id, role_category, seniority, fresher_friendly, employment_type,
                            work_mode, remote_scope, cities, countries, skills, tagger, enriched_at)
                        values (:p, 'backend', 'junior', true, 'full_time', 'hybrid', 'unknown', '{}', '{IN}',
                            '{Java}', 'fallback', now())
                        """).param("p", id).update();
        data.clear();

        HttpResponse<String> live = get("/live?role=backend");
        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(live.body()).contains("&lt;script&gt;alert(1)&lt;/script&gt; Backend Engineer", "Evil &amp; Co",
                "Junior · Hybrid", "found just now", "http-equiv=\"refresh\"");
        assertThat(live.body()).doesNotContain("<script>");
        assertThat(get("/live?role=frontend").body()).doesNotContain("Evil &amp; Co");

        for (String page : List.of("/", "/stats", "/status", "/privacy")) {
            HttpResponse<String> response = get(page);
            assertThat(response.statusCode()).as(page).isEqualTo(200);
            assertThat(response.body().length()).as(page + " stays under 100 KB").isLessThan(100_000);
            assertThat(response.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                    csp -> assertThat(csp).contains("default-src 'self'", "frame-ancestors 'none'"));
            assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
        }
        assertThat(get("/").body()).contains("boards checked", "https://t.me/EarlyBirdTestBot");
        assertThat(get("/stats").body()).contains("<svg").containsPattern("(<rect[^>]*class=\"bar[^\"]*\"[^>]*>[\\s\\S]*?){28}");
        assertThat(get("/status").body()).contains("Database", "OK", "not used (in-process event bus)");
    }

    @Test
    void thePublicApiIsJsonAndOpenToOtherSites() throws Exception {
        data.clear();
        HttpResponse<String> stats = get("/api/public/stats");
        assertThat(stats.statusCode()).isEqualTo(200);
        assertThat(stats.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        assertThat(stats.body()).contains("\"boardsChecked\"", "\"postingsPerDay\"", "\"deliveryP95\"");

        HttpResponse<String> cors = get("/api/public/postings", "Origin", "https://someone-else.example");
        assertThat(cors.statusCode()).isEqualTo(200);
        assertThat(cors.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(cors.body()).startsWith("[");
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
