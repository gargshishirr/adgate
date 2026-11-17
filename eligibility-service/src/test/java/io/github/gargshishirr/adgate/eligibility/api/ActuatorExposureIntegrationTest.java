package io.github.gargshishirr.adgate.eligibility.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.eligibility.security.ApiKeyFilter;

/**
 * Real HTTP on both ports: the API port only exposes the status-only probes, and metrics live on the separate
 * management port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "spring.data.redis.timeout=5s"
})
@AutoConfigureObservability(tracing = false)
class ActuatorExposureIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int apiPort;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.redisProperties(REDIS, registry);
        ApiTestSupport.apiKeyProperties(registry);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        REDIS.stop();
    }

    @Test
    void theApiPortHasPublicStatusOnlyProbes() throws Exception {
        for (String probe : new String[] {"/livez", "/readyz"}) {
            HttpResponse<String> response = send(apiPort, probe, null);
            assertThat(response.statusCode()).as(probe).isEqualTo(200);
            assertThat(response.body()).as(probe).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    void theApiPortDoesNotServeMetrics() throws Exception {
        assertThat(send(apiPort, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(send(apiPort, "/actuator/prometheus", ApiTestSupport.ADMIN_KEY).statusCode()).isEqualTo(404);
        assertThat(send(apiPort, "/actuator/circuitbreakers", ApiTestSupport.ADMIN_KEY).statusCode()).isEqualTo(404);
    }

    @Test
    void metricsAndHealthWithoutDetailsAreOnTheManagementPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(apiPort);

        HttpResponse<String> metrics = send(managementPort, "/actuator/prometheus", null);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("adgate_");

        HttpResponse<String> health = send(managementPort, "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).doesNotContain("components", "details", "redis");
    }

    @Test
    void aChunkedBodyIsCutOffAtTheSizeLimit() throws Exception {
        byte[] body = ("{\"requestId\":\"r-chunked\",\"query\":\"" + " ".repeat(200 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/v1/eligibility"))
                .header("Content-Type", "application/json")
                .header(ApiKeyFilter.API_KEY_HEADER, ApiTestSupport.CLIENT_KEY)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    private HttpResponse<String> send(int port, String path, String apiKey) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (apiKey != null) {
            request.header(ApiKeyFilter.API_KEY_HEADER, apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
