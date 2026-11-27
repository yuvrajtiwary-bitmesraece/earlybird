package io.github.yuvrajtiwary.earlybird.poller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class TickServiceTest {

    /**
     * Issue #5: every parallel board check holds a database connection, and the rest of the app (tagging,
     * matching, delivery, the website) needs some too. The tick must leave at least 2 of the pool for them.
     */
    @Test
    void parallelBoardChecksLeaveRoomInTheDatabasePool() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).getFirst();
        int poolSize = Integer.parseInt(String.valueOf(yaml.getProperty("spring.datasource.hikari.maximum-pool-size")));

        assertThat(poolSize).as("spec section 10: Hikari max 5").isEqualTo(5);
        assertThat(TickService.MAX_CONCURRENT_BOARDS).isBetween(1, poolSize - 2);
    }
}
