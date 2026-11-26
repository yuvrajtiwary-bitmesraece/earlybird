package io.github.yuvrajtiwary.earlybird.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.events.DeadLetter;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class DeadLetterNotifierTest {

    private final EventBus bus = mock(EventBus.class);
    private final AdminAlerts admin = mock(AdminAlerts.class);
    private final DeadLetterNotifier notifier = new DeadLetterNotifier(bus, admin);

    @Test
    void listensToTheDeadLetterTopic() {
        notifier.afterPropertiesSet();

        verify(bus).subscribe(eq(Topic.DLQ), eq("eb-admin"), eq(DeadLetter.class), ArgumentMatchers.any());
    }

    /** One notice per topic per day; the text is escaped, since error messages can contain anything. */
    @Test
    void tellsTheAdminWhoGaveUpOnWhat() {
        var letter = new DeadLetter("eb.alerts.requested.v1", 1, 42L, "7",
                "DeadLetterException: Telegram rejected alert: Bad Request: can't parse <b>", 1, Instant.now(), "e30=");

        notifier.onDeadLetter(EventEnvelope.of(letter, "eb-notifier", Clock.systemUTC()));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(admin).notifyOncePerDay(eq("dlq:eb.alerts.requested.v1"), html.capture());
        assertThat(html.getValue()).contains("<b>eb-notifier</b>", "<code>eb.alerts.requested.v1</code>",
                "after 1 attempt.", "can't parse &lt;b&gt;", "offset 42").doesNotContain("parse <b>");
    }
}
