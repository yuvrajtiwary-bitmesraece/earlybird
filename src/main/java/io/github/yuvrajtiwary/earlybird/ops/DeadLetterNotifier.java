package io.github.yuvrajtiwary.earlybird.ops;

import io.github.yuvrajtiwary.earlybird.common.AdminAlerts;
import io.github.yuvrajtiwary.earlybird.common.Html;
import io.github.yuvrajtiwary.earlybird.events.DeadLetter;
import io.github.yuvrajtiwary.earlybird.events.EventBus;
import io.github.yuvrajtiwary.earlybird.events.EventEnvelope;
import io.github.yuvrajtiwary.earlybird.events.Topic;
import org.springframework.beans.factory.InitializingBean;

/**
 * Dead letters → the admin (spec 3.11, "any DLQ message"). At most one notice a day per original topic, so a burst
 * of failures can't flood the chat; {@code /admin} shows how many dead letters there are. Runs only in processes
 * with a Telegram client (the bot or notifier role), as consumer group {@code eb-admin}.
 */
public class DeadLetterNotifier implements InitializingBean {

    static final String CONSUMER = "eb-admin";

    private final EventBus bus;
    private final AdminAlerts admin;

    public DeadLetterNotifier(EventBus bus, AdminAlerts admin) {
        this.bus = bus;
        this.admin = admin;
    }

    @Override
    public void afterPropertiesSet() {
        bus.subscribe(Topic.DLQ, CONSUMER, DeadLetter.class, this::onDeadLetter);
    }

    void onDeadLetter(EventEnvelope<DeadLetter> event) {
        DeadLetter d = event.payload();
        admin.notifyOncePerDay("dlq:" + d.originalTopic(), "Dead letter: <b>" + Html.escape(event.producer())
                + "</b> gave up on an event from <code>" + Html.escape(d.originalTopic()) + "</code> after "
                + d.attempts() + (d.attempts() == 1 ? " attempt" : " attempts") + ".\n<code>" + Html.escape(d.error())
                + "</code>\nIt is kept in <code>eb.dlq.v1</code> (partition " + d.partition() + ", offset " + d.offset()
                + " of the original). Further dead letters from this topic today only show in /admin.");
    }
}
