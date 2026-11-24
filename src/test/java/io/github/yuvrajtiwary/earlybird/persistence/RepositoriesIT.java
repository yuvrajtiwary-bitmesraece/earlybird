package io.github.yuvrajtiwary.earlybird.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.EmploymentType;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.RemoteScope;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Seniority;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.Tagger;
import io.github.yuvrajtiwary.earlybird.persistence.PostingTags.WorkMode;
import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Round trips through every repository against real Postgres. Each test uses fresh rows, so order doesn't matter. */
@IntegrationTest
class RepositoriesIT {

    // Microsecond precision, like timestamptz, so values survive the round trip unchanged.
    private static final Instant NOW = Instant.parse("2026-11-24T08:30:00.123456Z");
    private static final AtomicLong TELEGRAM_IDS = new AtomicLong(1_000_000);

    @Autowired JdbcClient jdbc;
    @Autowired BoardRepository boards;
    @Autowired PostingRepository postings;
    @Autowired PostingTagsRepository tags;
    @Autowired UserRepository users;
    @Autowired ProfileRepository profiles;
    @Autowired BotSessionRepository sessions;
    @Autowired AlertRepository alerts;
    @Autowired ProcessedEventRepository processedEvents;
    @Autowired TelegramUpdateRepository telegramUpdates;
    @Autowired TickRepository ticks;

    private Board newBoard() {
        String token = "board-" + UUID.randomUUID();
        assertThat(boards.insertIfAbsent(Source.GREENHOUSE, token, "ExampleCo")).isTrue();
        return boards.find(Source.GREENHOUSE, token).orElseThrow();
    }

    private Posting newPosting(Board board) {
        var posting = new Posting(Ids.newId(), board.id(), board.source(), "ext-" + UUID.randomUUID(),
                "https://boards.example.com/jobs/1", "Backend Engineer I", "ExampleCo", "Bengaluru, India",
                List.of("Engineering", "Platform"), null, NOW, NOW, 0, null, "hash-1", "Build APIs in Java.",
                false, null);
        assertThat(postings.insertIfAbsent(posting)).isTrue();
        return posting;
    }

    private User newUser() {
        long telegramId = TELEGRAM_IDS.incrementAndGet();
        return users.create(telegramId, telegramId, "Asha", "asha_dev", NOW);
    }

    private Alert alert(User user, Posting posting, Alert.Mode mode) {
        return new Alert(Ids.newId(), user.id(), posting.id(), 78, List.of("Java", "Spring Boot"),
                List.of("Kafka"), mode, Alert.Status.QUEUED, NOW, null, null, null, null);
    }

    private PostingTags tagsFor(Posting posting, int variant) {
        return new PostingTags(posting.id(),
                RoleCategory.values()[variant % RoleCategory.values().length],
                Seniority.values()[variant % Seniority.values().length],
                1.5, null, true,
                EmploymentType.values()[variant % EmploymentType.values().length],
                WorkMode.values()[variant % WorkMode.values().length],
                RemoteScope.values()[variant % RemoteScope.values().length],
                List.of("Bengaluru"), List.of("IN"), List.of("Java", "PostgreSQL"), "Backend role.",
                Tagger.values()[variant % Tagger.values().length], "gemini-3.5-flash-lite", NOW);
    }

    @Test
    void boardsAreUniquePerSourceAndToken() {
        Board board = newBoard();
        assertThat(boards.insertIfAbsent(Source.GREENHOUSE, board.token(), "ExampleCo again")).isFalse();
        assertThat(boards.insertIfAbsent(Source.LEVER, board.token(), "Same token, other platform")).isTrue();
        assertThat(board.enabled()).isTrue();
        assertThat(board.checkIntervalS()).isEqualTo(180);
        assertThat(boards.findEnabled()).extracting(Board::id).contains(board.id());
    }

    @Test
    void postingsRoundTripAndTheSameJobCannotBeInsertedTwice() {
        Posting posting = newPosting(newBoard());
        assertThat(postings.findById(posting.id())).contains(posting);

        var sameJobNewId = new Posting(Ids.newId(), posting.boardId(), posting.source(), posting.externalId(),
                posting.url(), posting.title(), posting.company(), posting.locationRaw(), posting.departments(),
                null, NOW, NOW, 0, null, "hash-1", null, false, null);
        assertThat(postings.insertIfAbsent(sameJobNewId)).isFalse();
        assertThat(postings.findById(sameJobNewId.id())).isEmpty();
    }

    @Test
    void tagsRoundTripAndAnUpsertReplacesThem() {
        Posting posting = newPosting(newBoard());
        tags.upsert(tagsFor(posting, 0));
        PostingTags retagged = tagsFor(posting, 1);
        tags.upsert(retagged);
        assertThat(tags.findByPostingId(posting.id())).contains(retagged);
    }

    @Test
    void everyJavaEnumValueSatisfiesTheDatabaseCheckConstraints() {
        Posting posting = newPosting(newBoard());
        // RoleCategory is the longest tag enum, so cycling through it with modulo covers every tag value.
        for (int v = 0; v < RoleCategory.values().length; v++) {
            tags.upsert(tagsFor(posting, v)); // throws if any value violates a CHECK constraint
        }
        for (Source source : Source.values()) {
            assertThat(boards.insertIfAbsent(source, "enum-" + UUID.randomUUID(), "EnumCo")).isTrue();
        }
        User user = newUser();
        for (User.Status status : User.Status.values()) {
            assertThat(users.updateStatus(user.id(), status)).isTrue();
        }
        for (Alert.Mode mode : Alert.Mode.values()) {
            assertThat(alerts.insertIfAbsent(alert(user, newPosting(newBoard()), mode))).isTrue();
        }
        for (String status : Sql.dbValues(Alert.Status.class)) {
            jdbc.sql("update alerts set status = :s where user_id = :u").param("s", status).param("u", user.id()).update();
        }
        for (String feedback : Sql.dbValues(Alert.Feedback.class)) {
            jdbc.sql("update alerts set feedback = :f where user_id = :u").param("f", feedback).param("u", user.id())
                    .update();
        }
        long tick = ticks.start(NOW);
        for (TickRepository.Status status : TickRepository.Status.values()) {
            ticks.finish(tick, NOW, 0, 0, 0, 0, status);
        }
    }

    @Test
    void theDatabaseRejectsASecondAlertForTheSameUserAndPosting() {
        User user = newUser();
        Posting posting = newPosting(newBoard());
        Alert first = alert(user, posting, Alert.Mode.INSTANT);

        assertThat(alerts.insertIfAbsent(first)).isTrue();
        assertThat(alerts.insertIfAbsent(alert(user, posting, Alert.Mode.DIGEST))).isFalse();
        // Even SQL that skips the repository's "on conflict" is refused by the unique constraint.
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into alerts (id, user_id, posting_id, score, mode)
                        values (:id, :user, :posting, 90, 'instant')
                        """)
                .param("id", Ids.newId()).param("user", user.id()).param("posting", posting.id())
                .update())
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(alerts.findById(first.id())).contains(first);
        assertThat(jdbc.sql("select count(*) from alerts where user_id = :u").param("u", user.id())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void usersAreUniquePerTelegramAccount() {
        User user = newUser();
        assertThat(users.findByTelegramUserId(user.telegramUserId())).contains(user);
        assertThat(user.status()).isEqualTo(User.Status.ONBOARDING);
        assertThatThrownBy(() -> users.create(user.telegramUserId(), 1, "Dup", null, NOW))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void profilesRoundTrip() {
        User user = newUser();
        var profile = new Profile(user.id(), List.of(RoleCategory.BACKEND, RoleCategory.FULLSTACK),
                List.of("Bengaluru", "Pune"), true, false, 1.5, true, List.of("Java", "Spring Boot", "PostgreSQL"),
                List.of(RoleCategory.BACKEND), 2026, 60, 8, "ab".repeat(32), NOW, NOW);
        profiles.upsert(profile);
        assertThat(profiles.findByUserId(user.id())).contains(profile);
    }

    @Test
    void botSessionsStoreJson() {
        User user = newUser();
        sessions.save(user.id(), "onboarding.roles", "{\"roles\": [\"backend\"]}", NOW);
        BotSession session = sessions.find(user.id()).orElseThrow();
        assertThat(session.state()).isEqualTo("onboarding.roles");
        assertThat(session.dataJson()).isEqualTo("{\"roles\": [\"backend\"]}");
        assertThat(sessions.delete(user.id())).isTrue();
    }

    @Test
    void deletingAUserErasesTheirProfileSessionAndAlerts() {
        User user = newUser();
        profiles.upsert(new Profile(user.id(), List.of(RoleCategory.BACKEND), List.of(), false, false, 0, false,
                List.of(), List.of(), null, 55, 10, null, null, NOW));
        sessions.save(user.id(), "idle", "{}", NOW);
        Alert alert = alert(user, newPosting(newBoard()), Alert.Mode.INSTANT);
        alerts.insertIfAbsent(alert);

        assertThat(users.delete(user.id())).isTrue();

        assertThat(users.findById(user.id())).isEmpty();
        assertThat(profiles.findByUserId(user.id())).isEmpty();
        assertThat(sessions.find(user.id())).isEmpty();
        assertThat(alerts.findById(alert.id())).isEmpty();
    }

    @Test
    void consumersAndWebhooksAreIdempotent() {
        UUID eventId = Ids.newId();
        assertThat(processedEvents.markProcessed(eventId, "eb-matcher")).isTrue();
        assertThat(processedEvents.markProcessed(eventId, "eb-matcher")).isFalse();
        assertThat(processedEvents.markProcessed(eventId, "eb-notifier")).isTrue(); // per consumer

        long updateId = TELEGRAM_IDS.incrementAndGet();
        assertThat(telegramUpdates.markReceived(updateId)).isTrue();
        assertThat(telegramUpdates.markReceived(updateId)).isFalse();
    }

    @Test
    void ticksRecordTheirOutcome() {
        long id = ticks.start(NOW);
        assertThat(ticks.findById(id).orElseThrow().status()).isEqualTo(TickRepository.Status.RUNNING);

        Instant finished = NOW.plusSeconds(12);
        ticks.finish(id, finished, 120, 2, 7, 3, TickRepository.Status.PARTIAL);

        assertThat(ticks.findById(id)).contains(
                new TickRepository.Tick(id, NOW, finished, 120, 2, 7, 3, TickRepository.Status.PARTIAL));
    }
}
