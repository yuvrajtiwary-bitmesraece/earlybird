package io.github.yuvrajtiwary.earlybird.sources;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** A {@link SourceHttp} with short timeouts and no retry delay, so failure tests run fast. */
final class TestHttp {

    static final String USER_AGENT = "EarlyBirdBot/1.0 (+https://github.com/yuvrajtiwary-bitmesraece/earlybird)";
    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private TestHttp() {}

    static SourceHttp fast() {
        return new SourceHttp(USER_AGENT, Duration.ofSeconds(2), Duration.ofMillis(800),
                List.of(Duration.ZERO, Duration.ZERO));
    }
}
