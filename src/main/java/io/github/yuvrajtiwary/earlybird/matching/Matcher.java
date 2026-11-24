package io.github.yuvrajtiwary.earlybird.matching;

import io.github.yuvrajtiwary.earlybird.common.Ids;
import io.github.yuvrajtiwary.earlybird.events.AlertRequested;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.PostingClosed;
import io.github.yuvrajtiwary.earlybird.events.PostingEnriched;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import io.github.yuvrajtiwary.earlybird.matching.MatchRules.Match;
import io.github.yuvrajtiwary.earlybird.matching.MatchRules.Route;
import io.github.yuvrajtiwary.earlybird.persistence.Alert;
import io.github.yuvrajtiwary.earlybird.persistence.AlertRepository;
import io.github.yuvrajtiwary.earlybird.persistence.MatchRepository;
import io.github.yuvrajtiwary.earlybird.persistence.MatchRepository.Candidate;
import io.github.yuvrajtiwary.earlybird.persistence.MatchRepository.TaggedPosting;
import io.github.yuvrajtiwary.earlybird.persistence.ProcessedEventRepository;
import io.github.yuvrajtiwary.earlybird.persistence.Profile;
import io.github.yuvrajtiwary.earlybird.persistence.ProfileRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The matcher (spec 3.9). On PostingEnriched: find candidate users, score each, create alerts best-first
 * (so the best matches go out first), then request delivery of the instant ones. On PostingClosed: cancel
 * alerts not yet sent. Also implements "recent matches" for a user who just onboarded or changed preferences.
 *
 * <p>Idempotent twice over: {@code processed_events} skips a redelivered event, and the unique
 * (user_id, posting_id) constraint makes a second alert for the same pair impossible anyway.
 */
public class Matcher implements InitializingBean, RecentMatches {

    private static final Logger log = LoggerFactory.getLogger(Matcher.class);
    static final String CONSUMER = "eb-matcher";
    static final Duration RECENT_WINDOW = Duration.ofHours(48);
    static final int RECENT_MAX = 3;

    private final MatchRepository matches;
    private final AlertRepository alerts;
    private final ProfileRepository profiles;
    private final ProcessedEventRepository processed;
    private final EventBus bus;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public Matcher(MatchRepository matches, AlertRepository alerts, ProfileRepository profiles,
            ProcessedEventRepository processed, EventBus bus, TransactionTemplate transaction, Clock clock,
            MeterRegistry meters) {
        this.matches = matches;
        this.alerts = alerts;
        this.profiles = profiles;
        this.processed = processed;
        this.bus = bus;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    /** Subscribed at startup, before any worker thread can publish. */
    @Override
    public void afterPropertiesSet() {
        bus.subscribe(Topic.JOBS_ENRICHED, CONSUMER, PostingEnriched.class, this::onEnriched);
        bus.subscribe(Topic.JOBS_CLOSED, CONSUMER, PostingClosed.class, this::onClosed);
    }

    private void onEnriched(EventEnvelope<PostingEnriched> event) {
        UUID postingId = event.payload().postingId();
        List<AlertRequested> instant = transaction.execute(status -> {
            if (!processed.markProcessed(event.eventId(), CONSUMER)) {
                return List.<AlertRequested>of(); // already handled this event
            }
            return createAlerts(postingId);
        });
        instant.forEach(a -> request(a, event));
    }

    private void onClosed(EventEnvelope<PostingClosed> event) {
        transaction.executeWithoutResult(status -> {
            if (processed.markProcessed(event.eventId(), CONSUMER)) {
                int cancelled = alerts.cancelQueuedForPosting(event.payload().postingId());
                if (cancelled > 0) {
                    log.info("Posting {} closed: {} queued alerts cancelled", event.payload().postingId(), cancelled);
                }
            }
        });
    }

    /** Scores every candidate and inserts alerts best-first. Returns the instant ones to deliver. */
    List<AlertRequested> createAlerts(UUID postingId) {
        Optional<TaggedPosting> found = matches.findTagged(postingId);
        if (found.isEmpty() || found.get().posting().closedAt() != null || found.get().posting().repost()) {
            return List.of();
        }
        TaggedPosting tp = found.get();
        record Scored(Candidate candidate, Match match, Route route) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : matches.candidates(tp.tags().roleCategory(), postingId)) {
            MatchRules.evaluate(tp.tags(), c.profile()).ifPresent(m -> {
                Route route = MatchRules.route(m.score(), c.profile().minScore(), tp.posting().baseline());
                if (route != Route.NONE) {
                    scored.add(new Scored(c, m, route));
                }
            });
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.match().score()).reversed());

        List<AlertRequested> instant = new ArrayList<>();
        Instant now = clock.instant();
        for (Scored s : scored) {
            Alert.Mode mode = s.route() == Route.INSTANT ? Alert.Mode.INSTANT : Alert.Mode.DIGEST;
            Alert alert = newAlert(s.candidate().userId(), postingId, s.match(), mode, now);
            if (alerts.insertIfAbsent(alert)) {
                meters.counter("earlybird.alerts", "mode", mode.name().toLowerCase(), "result", "created").increment();
                if (mode == Alert.Mode.INSTANT) {
                    instant.add(requestFor(alert, false));
                }
            }
        }
        if (!scored.isEmpty()) {
            log.info("Posting {}: {} matches ({} instant)", postingId, scored.size(), instant.size());
        }
        return instant;
    }

    /** Recent matches: the best few postings from the last 48 h this user hasn't been sent, as instant alerts. */
    @Override
    public int sendTo(long userId) {
        Optional<Profile> profile = profiles.findByUserId(userId);
        if (profile.isEmpty() || profile.get().roles().isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        record Pick(TaggedPosting posting, Match match) {}
        List<Pick> best = matches.recentFor(userId, profile.get().roles(), now.minus(RECENT_WINDOW)).stream()
                .flatMap(tp -> MatchRules.evaluate(tp.tags(), profile.get()).stream().map(m -> new Pick(tp, m)))
                .filter(p -> p.match().score() >= MatchRules.DIGEST_FLOOR)
                .sorted(Comparator.comparingInt((Pick p) -> p.match().score()).reversed())
                .limit(RECENT_MAX)
                .toList();
        int sent = 0;
        for (Pick c : best) {
            Alert alert = newAlert(userId, c.posting().posting().id(), c.match(), Alert.Mode.INSTANT, now);
            if (alerts.insertIfAbsent(alert)) {
                request(requestFor(alert, true), null);
                sent++;
            }
        }
        return sent;
    }

    private static Alert newAlert(long userId, UUID postingId, Match m, Alert.Mode mode, Instant now) {
        return new Alert(Ids.newId(), userId, postingId, m.score(), m.matchedSkills(), m.missingSkills(), mode,
                Alert.Status.QUEUED, now, null, null, null, null);
    }

    private static AlertRequested requestFor(Alert a, boolean recent) {
        return new AlertRequested(a.id(), a.userId(), a.postingId(), a.score(), a.matchedSkills(), a.missingSkills(), recent);
    }

    private void request(AlertRequested request, EventEnvelope<?> cause) {
        var envelope = cause == null ? EventEnvelope.of(request, "matcher", clock) : cause.derive(request, "matcher", clock);
        try {
            bus.publish(Topic.ALERTS_REQUESTED, String.valueOf(request.userId()), envelope).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The alert row stays 'queued'; Block 5's relay re-requests queued instant alerts older than 2 minutes.
            log.warn("Could not request delivery of alert {}: {}", request.alertId(), e.toString());
        }
    }
}
