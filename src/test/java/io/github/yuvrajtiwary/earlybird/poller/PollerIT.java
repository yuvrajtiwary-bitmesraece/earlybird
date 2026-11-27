package io.github.yuvrajtiwary.earlybird.poller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.PostingClosed;
import io.github.yuvrajtiwary.earlybird.events.PostingDiscovered;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import io.github.yuvrajtiwary.earlybird.persistence.Board;
import io.github.yuvrajtiwary.earlybird.persistence.BoardRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Posting;
import io.github.yuvrajtiwary.earlybird.persistence.PostingRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Source;
import io.github.yuvrajtiwary.earlybird.persistence.TickRepository;
import io.github.yuvrajtiwary.earlybird.poller.BoardCheckResult.Outcome;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The poller against real Postgres and a fake Greenhouse board (WireMock), through every rule: baseline,
 * new jobs, 304s, closing and reopening, the empty-board guard, reposts, errors and a full tick.
 */
@IntegrationTest
class PollerIT {

    private static final List<EventEnvelope<?>> EVENTS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean SUBSCRIBED = new AtomicBoolean();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired BoardChecker checker;
    @Autowired TickService ticks;
    @Autowired BoardRepository boards;
    @Autowired PostingRepository postings;
    @Autowired TickRepository tickRepository;
    @Autowired EventBus bus;
    @Autowired WireMockServer fakeBoards;

    record Job(String id, String title, Instant firstPublished, String text) {}

    @BeforeEach
    void listenToEvents() {
        if (SUBSCRIBED.compareAndSet(false, true)) {
            bus.subscribe(Topic.JOBS_DISCOVERED, "poller-it-discovered", PostingDiscovered.class, EVENTS::add);
            bus.subscribe(Topic.JOBS_CLOSED, "poller-it-closed", PostingClosed.class, EVENTS::add);
        }
    }

    // ---------------------------------------------------------------------------------------------- tests

    @Test
    void theFirstCheckStoresABaselineAndOnlyAnnouncesRecentJobs() {
        Board board = newBoard();
        stubBaseline(board, job("1", Duration.ofHours(2)), job("2", Duration.ofDays(10)), job("3", Duration.ofDays(30)));

        BoardCheckResult result = checker.check(board);

        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(result.newPostings()).isEqualTo(3);
        Map<String, Posting> stored = stored(board);
        assertThat(stored.values()).allMatch(Posting::baseline);
        // Published 2 h ago: kept for tagging and announced (as baseline).
        assertThat(stored.get("1").descriptionText()).contains("Build service 1");
        assertThat(stored.get("1").eventPublishedAt()).isNotNull();
        // Older: stored only, to detect closures later. No description kept, nothing announced.
        assertThat(stored.get("2").descriptionText()).isNull();
        assertThat(stored.get("2").eventPublishedAt()).isNull();
        assertThat(stored.get("3").eventPublishedAt()).isNull();

        await().untilAsserted(() -> assertThat(discovered(board)).extracting(PostingDiscovered::externalId)
                .containsExactly("1"));
        assertThat(discovered(board).getFirst().baseline()).isTrue();
        Board after = reload(board);
        assertThat(after.lastSuccessAt()).isNotNull();
        assertThat(after.lastJobCount()).isEqualTo(3);
    }

    @Test
    void laterChecksAnnounceNewJobsSkipUnchangedBoardsAndCloseMissingJobs() {
        Board board = newBoard();
        Job one = job("1", Duration.ofDays(30));
        Job two = job("2", Duration.ofDays(30));
        Job three = job("3", Duration.ofMinutes(5));
        stubBaseline(board, one, two);
        checker.check(board);

        // A new job appears: fetched in detail, stored as non-baseline, announced.
        stubList(board, "W/\"v2\"", one, two, three);
        stubDetail(board, three);
        assertThat(checker.check(reload(board)).newPostings()).isEqualTo(1);
        Posting fresh = stored(board).get("3");
        assertThat(fresh.baseline()).isFalse();
        assertThat(fresh.descriptionText()).contains("Build service 3");
        assertThat(fresh.eventPublishedAt()).isNotNull();
        await().untilAsserted(() -> assertThat(discovered(board)).extracting(PostingDiscovered::externalId)
                .containsExactly("3"));
        assertThat(reload(board).etag()).isEqualTo("W/\"v2\"");

        // Nothing changed: the ETag gets a 304 and nothing is parsed.
        stubNotModified(board, "W/\"v2\"");
        assertThat(checker.check(reload(board)).outcome()).isEqualTo(Outcome.NOT_MODIFIED);

        // Job 1 disappears: one miss is tolerated, the second closes it.
        stubList(board, "W/\"v3\"", two, three);
        assertThat(checker.check(reload(board)).closedPostings()).isZero();
        assertThat(stored(board).get("1").missingCount()).isEqualTo(1);
        assertThat(stored(board).get("1").closedAt()).isNull();

        assertThat(checker.check(reload(board)).closedPostings()).isEqualTo(1);
        Posting closed = stored(board).get("1");
        assertThat(closed.closedAt()).isNotNull();
        await().untilAsserted(() -> assertThat(closedEvents())
                .extracting(PostingClosed::postingId).contains(closed.id()));

        // It comes back (boards occasionally drop a job briefly): reopened, not re-announced.
        stubList(board, "W/\"v4\"", one, two, three);
        assertThat(checker.check(reload(board)).newPostings()).isZero();
        assertThat(stored(board).get("1").closedAt()).isNull();
        assertThat(stored(board).get("1").missingCount()).isZero();
    }

    @Test
    void aBoardThatSuddenlyListsNothingIsIgnoredTwiceThenBelieved() {
        Board board = newBoard();
        stubBaseline(board, job("1", Duration.ofDays(9)), job("2", Duration.ofDays(9)), job("3", Duration.ofDays(9)),
                job("4", Duration.ofDays(9)), job("5", Duration.ofDays(9)));
        checker.check(board);
        stubList(board, "W/\"empty\"");

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThat(checker.check(reload(board)).outcome()).isEqualTo(Outcome.SUSPICIOUS_EMPTY);
            assertThat(stored(board).values()).allMatch(p -> p.missingCount() == 0 && p.closedAt() == null);
            assertThat(reload(board).consecutiveEmptyChecks()).isEqualTo(attempt);
            assertThat(reload(board).consecutiveFailures()).isEqualTo(attempt);
        }

        BoardCheckResult believed = checker.check(reload(board));
        assertThat(believed.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(stored(board).values()).allMatch(p -> p.missingCount() == 1 && p.closedAt() == null);
        assertThat(reload(board).consecutiveFailures()).isZero();

        assertThat(checker.check(reload(board)).closedPostings()).isEqualTo(5);
    }

    @Test
    void aRepostIsStoredButNotAnnounced() {
        Board board = newBoard();
        Job original = new Job("1", "Backend Engineer", Instant.now().minus(Duration.ofDays(20)), "Build payments APIs");
        stubBaseline(board, original);
        checker.check(board);

        // Same title, place and text under a new id: the company bumped the job to the top.
        Job bumped = new Job("2", original.title(), Instant.now(), original.text());
        stubList(board, "W/\"r2\"", original, bumped);
        stubDetail(board, bumped);
        assertThat(checker.check(reload(board)).newPostings()).isEqualTo(1);

        Posting repost = stored(board).get("2");
        assertThat(repost.repost()).isTrue();
        assertThat(repost.contentHash()).isEqualTo(stored(board).get("1").contentHash());
        assertThat(repost.eventPublishedAt()).isNull();
        assertThat(repost.descriptionText()).isNull();
    }

    @Test
    void aBoardErrorIsRecordedAndTheNextCheckIsStillTheBaseline() {
        Board board = newBoard(); // nothing stubbed: WireMock answers 404

        BoardCheckResult result = checker.check(board);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        Board after = reload(board);
        assertThat(after.consecutiveFailures()).isEqualTo(1);
        assertThat(after.lastError()).contains("404");
        assertThat(after.lastSuccessAt()).isNull();
        assertThat(stored(board)).isEmpty();
    }

    @Test
    void aTickChecksTheBoardsThatAreDueAndRecordsItself() {
        Board board = newBoard();
        stubBaseline(board, job("1", Duration.ofHours(1)));
        int due = boards.findDue(java.time.Instant.now()).size();

        TickService.TickSummary summary = ticks.runTick();

        assertThat(summary.boardsChecked()).isEqualTo(due);
        assertThat(reload(board).lastSuccessAt()).isNotNull();
        TickRepository.Tick tick = tickRepository.findById(summary.tickId()).orElseThrow();
        assertThat(tick.finishedAt()).isNotNull();
        assertThat(tick.boardsChecked()).isEqualTo(due);
        assertThat(tick.status()).isNotEqualTo(TickRepository.Status.RUNNING);

        // Just checked, so not due again until its next slot: an immediate second tick leaves it alone.
        java.time.Instant checkedAt = reload(board).lastCheckedAt();
        ticks.runTick();
        assertThat(reload(board).lastCheckedAt()).isEqualTo(checkedAt);
        assertThat(boards.findDue(java.time.Instant.now())).extracting(Board::id).doesNotContain(board.id());
    }

    // -------------------------------------------------------------------------------------------- helpers

    /** Each test gets its own board and company, so content fingerprints never collide across tests. */
    private Board newBoard() {
        String token = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        boards.insertIfAbsent(Source.GREENHOUSE, token, "Acme " + token);
        return boards.find(Source.GREENHOUSE, token).orElseThrow();
    }

    private Board reload(Board board) {
        return boards.findById(board.id()).orElseThrow();
    }

    private Map<String, Posting> stored(Board board) {
        Map<String, Posting> byExternalId = new LinkedHashMap<>();
        postings.findByBoard(board.id()).forEach(p -> byExternalId.put(p.externalId(), p));
        return byExternalId;
    }

    private static Job job(String id, Duration age) {
        return new Job(id, "Engineer " + id, Instant.now().minus(age).truncatedTo(ChronoUnit.SECONDS),
                "Build service " + id + " in Java and PostgreSQL.");
    }

    private static List<PostingDiscovered> discovered(Board board) {
        return EVENTS.stream()
                .map(EventEnvelope::payload)
                .filter(p -> p instanceof PostingDiscovered d && d.boardToken().equals(board.token()))
                .map(PostingDiscovered.class::cast)
                .toList();
    }

    private static List<PostingClosed> closedEvents() {
        return EVENTS.stream().map(EventEnvelope::payload)
                .filter(PostingClosed.class::isInstance).map(PostingClosed.class::cast).toList();
    }

    private static String path(Board board) {
        return "/v1/boards/" + board.token() + "/jobs";
    }

    /** Greenhouse list JSON. Content is HTML-escaped HTML, exactly as Greenhouse sends it. */
    private static String listJson(boolean withContent, Job... jobs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Job j : jobs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Long.parseLong(j.id()));
            m.put("title", j.title());
            m.put("absolute_url", "https://boards.example.com/jobs/" + j.id());
            m.put("location", Map.of("name", "Bengaluru, India"));
            m.put("first_published", j.firstPublished().toString());
            if (withContent) {
                m.put("content", escapedHtml(j));
                m.put("departments", List.of(Map.of("name", "Engineering")));
            }
            list.add(m);
        }
        return JSON.writeValueAsString(Map.of("jobs", list, "meta", Map.of("total", list.size())));
    }

    private static String escapedHtml(Job job) {
        return "&lt;div class=&quot;content-intro&quot;&gt;We are Acme.&lt;/div&gt;&lt;p&gt;" + job.text() + "&lt;/p&gt;";
    }

    private void stubBaseline(Board board, Job... jobs) {
        fakeBoards.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", equalTo("true"))
                .willReturn(okJson(listJson(true, jobs))));
    }

    /** The light list (no content). Stubs added later win, so this replaces earlier list stubs. */
    private void stubList(Board board, String etag, Job... jobs) {
        fakeBoards.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", absent())
                .willReturn(okJson(listJson(false, jobs)).withHeader("ETag", etag)));
    }

    private void stubNotModified(Board board, String etag) {
        fakeBoards.stubFor(get(urlPathEqualTo(path(board))).withQueryParam("content", absent())
                .withHeader("If-None-Match", equalTo(etag))
                .willReturn(aResponse().withStatus(304)));
    }

    private void stubDetail(Board board, Job job) {
        String body = JSON.writeValueAsString(Map.of("id", Long.parseLong(job.id()), "content", escapedHtml(job),
                "departments", List.of(Map.of("name", "Engineering"))));
        fakeBoards.stubFor(get(urlPathEqualTo(path(board) + "/" + job.id())).willReturn(okJson(body)));
    }
}
