package io.github.yuvrajtiwary.earlybird.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.yuvrajtiwary.earlybird.common.redis.RedisGuard;
import io.github.yuvrajtiwary.earlybird.enrich.GeminiBudget;
import io.github.yuvrajtiwary.earlybird.events.KafkaMonitor;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository.Count;
import io.github.yuvrajtiwary.earlybird.persistence.PublicStatsRepository.Percentiles;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The numbers behind the website and the public API, cached for 60 s (spec 3.12): a page view costs no queries
 * most of the time, which matters on a 5-connection pool and a tenth of a CPU. Pages and JSON share these views.
 */
public class PublicData {

    static final Duration CACHE = Duration.ofSeconds(60);
    static final int LIVE_LIMIT = 50;
    static final int DAYS = 14;

    public record Home(int boards, int postings24h, int alerts7d, String p95Delivery) {}

    public record LiveRow(String company, String title, String location, String tags, String role, String found,
            String url, Instant firstSeenAt) {}

    /** One SVG bar, positioned in a 0..600 × 0..160 drawing. */
    public record Bar(String day, int value, int x, int y, int width, int height) {}

    public record Chart(List<Bar> bars, int max, int total) {}

    public record Stats(Chart postings, Chart alerts, String deliveryP50, String deliveryP95, int deliveryCount,
            String publishedP50, String publishedP95, int publishedCount, int relevant, int notRelevant,
            String relevancePercent, List<Count> topCompanies, List<Count> topSkills) {}

    public record Status(boolean database, String redis, String kafka, String lastTick, String gemini) {}

    private final PublicStatsRepository stats;
    private final ObjectProvider<KafkaMonitor> kafka;
    private final ObjectProvider<RedisGuard> redis;
    private final ObjectProvider<GeminiBudget> gemini;
    private final Clock clock;
    private final Cache<String, Object> cache;

    public PublicData(PublicStatsRepository stats, ObjectProvider<KafkaMonitor> kafka, ObjectProvider<RedisGuard> redis,
            ObjectProvider<GeminiBudget> gemini, Clock clock) {
        this.stats = stats;
        this.kafka = kafka;
        this.redis = redis;
        this.gemini = gemini;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(CACHE).maximumSize(50).build();
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> load) {
        return (T) cache.get(key, k -> load.get());
    }

    public Home home() {
        return cached("home", () -> {
            Instant now = clock.instant();
            return new Home(stats.enabledBoards(), stats.newPostingsSince(now.minus(Duration.ofHours(24))),
                    stats.alertsSentSince(now.minus(Duration.ofDays(7))),
                    Format.seconds(stats.deliveryLag(now.minus(Duration.ofDays(7)), now).p95()));
        });
    }

    /** @param role a database role value, or null for all */
    public List<LiveRow> live(String role) {
        return cached("live:" + role, () -> {
            Instant now = clock.instant();
            return stats.latest(role, LIVE_LIMIT).stream()
                    .map(p -> new LiveRow(p.company(), p.title(), p.location() == null ? "" : p.location(),
                            p.role() == null ? "tagging…" : Format.tags(p.seniority(), p.workMode(), p.minYears(), p.maxYears()),
                            p.role(), Format.ago(p.firstSeenAt(), now), p.url(), p.firstSeenAt()))
                    .toList();
        });
    }

    public Stats stats() {
        return cached("stats", () -> {
            Instant now = clock.instant();
            Instant week = now.minus(Duration.ofDays(7));
            LocalDate today = LocalDate.ofInstant(now, Format.IST);
            LocalDate first = today.minusDays(DAYS - 1);
            Instant since = first.atStartOfDay(Format.IST).toInstant();
            Percentiles delivery = stats.deliveryLag(week, now);
            Percentiles published = stats.publishedToAlert(week, now);
            var relevance = stats.relevance(now.minus(Duration.ofDays(30)), now);
            int rated = relevance.relevant() + relevance.notRelevant();
            return new Stats(chart(stats.postingsPerDay(since), first), chart(stats.alertsPerDay(since), first),
                    Format.seconds(delivery.p50()), Format.seconds(delivery.p95()), delivery.count(),
                    Format.seconds(published.p50()), Format.seconds(published.p95()), published.count(),
                    relevance.relevant(), relevance.notRelevant(),
                    rated == 0 ? "–" : Math.round(100.0 * relevance.relevant() / rated) + "%",
                    stats.topCompanies(week, now, 10), stats.topFresherSkills(week, now, 10));
        });
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** 14 bars, one per India day (days with nothing get an empty bar), scaled to the busiest day. */
    static Chart chart(Map<LocalDate, Integer> perDay, LocalDate first) {
        int max = 0;
        int total = 0;
        for (int i = 0; i < DAYS; i++) {
            int v = perDay.getOrDefault(first.plusDays(i), 0);
            max = Math.max(max, v);
            total += v;
        }
        List<Bar> bars = new ArrayList<>();
        int slot = 600 / DAYS;
        for (int i = 0; i < DAYS; i++) {
            LocalDate day = first.plusDays(i);
            int v = perDay.getOrDefault(day, 0);
            int h = max == 0 ? 0 : Math.max(v > 0 ? 2 : 0, Math.round(140f * v / max));
            bars.add(new Bar(DAY.format(day), v, i * slot + 4, 140 - h, slot - 8, h));
        }
        return new Chart(bars, max, total);
    }

    public Status status() {
        return cached("status", () -> {
            boolean db;
            try {
                db = stats.databaseUp();
            } catch (RuntimeException e) {
                db = false;
            }
            KafkaMonitor monitor = kafka.getIfAvailable();
            RedisGuard guard = redis.getIfAvailable();
            GeminiBudget budget = gemini.getIfAvailable();
            String lastTick;
            try {
                lastTick = stats.lastSuccessfulTick().map(Format::when).orElse("none yet");
            } catch (RuntimeException e) {
                lastTick = "unknown";
            }
            return new Status(db,
                    guard == null ? "not used (in-memory fallbacks)" : guard.coolingDown() ? "unreachable (using fallbacks)" : "OK",
                    monitor == null ? "not used (in-process event bus)" : monitor.summary(),
                    lastTick,
                    budget == null ? "not in this process" : budget.remainingToday() + " of " + budget.maxPerDay() + " calls left today");
        });
    }

    /** For tests and the admin: forget everything cached. */
    void clear() {
        cache.invalidateAll();
    }
}
