package io.github.gargshishirr.adgate.eligibility.api;

import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectDetailContains;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectReason;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.freshSpendWatermarks;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.putCampaign;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.requestEligibility;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.fppt.jedismock.RedisServer;

/** Proves the fail-closed path: when Redis disappears, nothing serves, and the circuit breaker keeps latency flat. */
@SpringBootTest(properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "adgate.latency-budget=5s",
        "spring.data.redis.timeout=300ms",
        "resilience4j.circuitbreaker.instances.redis.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.redis.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.redis.slow-call-duration-threshold=5s",
        "resilience4j.circuitbreaker.instances.redis.wait-duration-in-open-state=1m"
})
@AutoConfigureMockMvc
class RedisOutageIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        ApiTestSupport.redisProperties(REDIS, registry);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @Test
    void failsClosedAndThenShortCircuitsWhileRedisIsDown() throws Exception {
        putCampaign(mvc, "c-1", "{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":50000000,\"pacing\":\"ASAP\"}");
        freshSpendWatermarks(redis, System.currentTimeMillis());
        expectReason(requestEligibility(mvc, "r-healthy", "photo editor", "c-1"), 0, "ELIGIBLE");

        REDIS.stop();

        for (int i = 0; i < 4; i++) {
            expectReason(requestEligibility(mvc, "r-outage-" + i, "photo editor", "c-1"), 0, "DEPENDENCY_UNAVAILABLE");
        }

        long start = System.nanoTime();
        var shortCircuited = requestEligibility(mvc, "r-open", "photo editor", "c-1");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        expectReason(shortCircuited, 0, "DEPENDENCY_UNAVAILABLE");
        expectDetailContains(shortCircuited, 0, "CIRCUIT_OPEN");
        assertThat(elapsed).isLessThan(Duration.ofMillis(200));
    }
}
