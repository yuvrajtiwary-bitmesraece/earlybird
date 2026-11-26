package io.github.yuvrajtiwary.earlybird.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Like {@link IntegrationTest}, but with {@code EVENT_BUS=kafka} against a real Kafka in Docker. A separate
 * Spring context (and database) from the in-process-bus tests. Retries are shortened from 1 s, 5 s, 30 s to
 * 100 ms each, and the admin has a chat id, so dead-letter notices can be seen at the fake Telegram.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@IntegrationTest
@Import(KafkaTestcontainers.class)
@TestPropertySource(properties = {
    "earlybird.events.bus=kafka",
    "earlybird.events.retry-backoff=100ms,100ms,100ms",
    "earlybird.telegram.admin-chat-id=" + KafkaIntegrationTest.ADMIN_CHAT,
})
public @interface KafkaIntegrationTest {

    long ADMIN_CHAT = 424_242;
}
