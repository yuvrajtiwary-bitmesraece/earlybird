package io.github.yuvrajtiwary.earlybird;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class HealthIT {

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void readinessIsUpWhenTheDatabaseIsReachable() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthDetailsAreNotPublic() throws Exception {
        assertThat(get("/actuator/health").body()).doesNotContain("postgres", "PostgreSQL", "components");
    }
}
