package io.github.yuvrajtiwary.earlybird.telegram;

import io.github.yuvrajtiwary.earlybird.common.RateLimiter;
import io.github.yuvrajtiwary.earlybird.common.RateLimiter.Bandwidth;
import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiBudget;
import io.github.yuvrajtiwary.earlybird.events.KafkaMonitor;
import io.github.yuvrajtiwary.earlybird.ops.DistributedLock;
import io.github.yuvrajtiwary.earlybird.ops.InternalScheduler;
import io.github.yuvrajtiwary.earlybird.persistence.OpsStatsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.TickRepository;
import io.github.yuvrajtiwary.earlybird.poller.TickService;
import io.github.yuvrajtiwary.earlybird.telegram.api.TelegramApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Commands only {@code ADMIN_TELEGRAM_CHAT_ID} can use (spec 3.11). */
class AdminCommands {

    private static final Logger log = LoggerFactory.getLogger(AdminCommands.class);
    private static final DateTimeFormatter IST = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.of("Asia/Kolkata"));

    private final TelegramApi api;
    private final OpsStatsRepository stats;
    private final ObjectProvider<GeminiBudget> budget;
    private final ObjectProvider<TickService> ticks;
    private final ObjectProvider<KafkaMonitor> kafka;
    private final ObjectProvider<RedisGuard> redis;
    private final DistributedLock lock;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    AdminCommands(TelegramApi api, OpsStatsRepository stats, ObjectProvider<GeminiBudget> budget,
            ObjectProvider<TickService> ticks, ObjectProvider<KafkaMonitor> kafka, ObjectProvider<RedisGuard> redis,
            DistributedLock lock, RateLimiter rateLimiter, Clock clock) {
        this.api = api;
        this.stats = stats;
        this.budget = budget;
        this.ticks = ticks;
        this.kafka = kafka;
        this.redis = redis;
        this.lock = lock;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** /admin */
    void status(long chatId) {
        Instant dayAgo = clock.instant().minus(Duration.ofHours(24));
        StringBuilder text = new StringBuilder("🛠 <b>EarlyBird status</b>\n");
        stats.latestTick().ifPresentOrElse(t -> text.append("<b>Last tick:</b> #").append(t.id()).append(" at ")
                .append(IST.format(t.startedAt())).append(" IST · ").append(t.status().name().toLowerCase())
                .append(" · ").append(t.boardsChecked()).append(" boards, ").append(t.boardsFailed())
                .append(" failed, ").append(t.newPostings()).append(" new").append(took(t)).append("\n"),
                () -> text.append("<b>Last tick:</b> none yet\n"));
        var boards = stats.boardHealth();
        text.append("<b>Boards:</b> ").append(boards.enabled() - boards.failing()).append(" OK, ")
                .append(boards.failing()).append(" failing\n")
                .append("<b>Last 24 h:</b> ").append(stats.newPostingsSince(dayAgo)).append(" new postings, ")
                .append(stats.alertsSince(dayAgo)).append(" alerts\n")
                .append("<b>Active users:</b> ").append(stats.activeUsers()).append("\n");
        GeminiBudget gemini = budget.getIfAvailable();
        if (gemini != null) {
            text.append("<b>Gemini calls left today:</b> ").append(gemini.remainingToday()).append(" of ")
                    .append(gemini.maxPerDay()).append("\n");
        }
        KafkaMonitor monitor = kafka.getIfAvailable();
        if (monitor == null) {
            text.append("<b>Kafka:</b> not used (in-process event bus)\n");
        } else {
            text.append("<b>Kafka:</b> ").append(monitor.summary());
            monitor.deadLetters().ifPresent(n -> text.append(" · ").append(n).append(n == 1 ? " dead letter" : " dead letters"));
            text.append("\n");
        }
        RedisGuard guard = redis.getIfAvailable();
        text.append("<b>Redis:</b> ").append(guard == null ? "not configured (in-memory fallbacks)"
                : guard.coolingDown() ? "unreachable, using fallbacks" : "OK").append("\n");
        api.sendMessage(chatId, text.toString(), null);
    }

    /** /admin_tick: run a tick now (handy in demos). At most once a minute, and never alongside another tick. */
    void tick(long chatId) {
        TickService tickService = ticks.getIfAvailable();
        if (tickService == null) {
            api.sendMessage(chatId, "This process doesn't run the poller role.", null);
            return;
        }
        if (!rateLimiter.tryAcquire("admin:tick", new Bandwidth(1, 1.0 / 60)).isZero()) {
            api.sendMessage(chatId, "At most one manual tick a minute.", null);
            return;
        }
        var handle = lock.tryAcquire(InternalScheduler.TICK_LOCK, InternalScheduler.TICK_LOCK_TTL);
        if (handle.isEmpty()) {
            api.sendMessage(chatId, "A tick is already running.", null);
            return;
        }
        api.sendMessage(chatId, "⏱ Tick started…", null);
        Thread.ofVirtual().name("admin-tick").start(() -> {
            try (var held = handle.get()) {
                TickService.TickSummary s = tickService.runTick();
                api.sendMessage(chatId, "✅ Tick #" + s.tickId() + ": " + s.boardsChecked() + " boards ("
                        + s.boardsUnchanged() + " unchanged, " + s.boardsFailed() + " failed), " + s.newPostings()
                        + " new, " + s.closedPostings() + " closed, " + s.took().toMillis() / 1000 + " s", null);
            } catch (RuntimeException e) {
                log.error("Manual tick failed", e);
                api.sendMessage(chatId, "❌ Tick failed: " + e.getClass().getSimpleName(), null);
            }
        });
    }

    private static String took(TickRepository.Tick t) {
        return t.finishedAt() == null ? ""
                : " · " + Duration.between(t.startedAt(), t.finishedAt()).toSeconds() + " s";
    }
}
