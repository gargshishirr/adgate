package io.github.gargshishirr.adgate.eligibility.api;

import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.expectReason;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.freshSpendWatermarks;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.putCampaign;
import static io.github.gargshishirr.adgate.eligibility.api.ApiTestSupport.requestEligibility;

import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;

/** Campaign configuration freshness: idle campaigns stay fresh, and a corrupt config only fails its own campaign. */
@SpringBootTest(properties = {
        "adgate.warm-up=0s",
        "adgate.decisions.publish=false",
        "adgate.spend.discover-partitions=false",
        "adgate.latency-budget=5s",
        "adgate.config-refresh=200ms",
        "adgate.engine.max-config-age=1s",
        "spring.data.redis.timeout=5s",
        "resilience4j.circuitbreaker.instances.redis.slow-call-duration-threshold=5s"
})
@AutoConfigureMockMvc
class ConfigFreshnessIntegrationTest {

    private static final RedisServer REDIS = ApiTestSupport.startRedis();
    private static final String ASAP_CAMPAIGN = "{\"status\":\"ACTIVE\",\"dailyBudgetMicros\":50000000,\"pacing\":\"ASAP\"}";

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
        REDIS.stop();
    }

    @BeforeEach
    void resetState() {
        ApiTestSupport.flush(redis);
    }

    @Test
    void idleCampaignsStayFreshBecauseConfigsRefreshInTheBackground() throws Exception {
        putCampaign(mvc, "c-idle", ASAP_CAMPAIGN);
        freshSpendWatermarks(redis, System.currentTimeMillis());
        expectReason(requestEligibility(mvc, "r-before-idle", "photo editor", "c-idle"), 0, "ELIGIBLE");

        Thread.sleep(1_500); // no traffic for longer than the allowed config age

        freshSpendWatermarks(redis, System.currentTimeMillis());
        expectReason(requestEligibility(mvc, "r-after-idle", "photo editor", "c-idle"), 0, "ELIGIBLE");
    }

    @Test
    void aCorruptConfigurationOnlyFailsItsOwnCampaign() throws Exception {
        putCampaign(mvc, "c-good", ASAP_CAMPAIGN);
        putCampaign(mvc, "c-corrupt", ASAP_CAMPAIGN);
        freshSpendWatermarks(redis, System.currentTimeMillis());
        var before = requestEligibility(mvc, "r-before-corruption", "photo editor", "c-good", "c-corrupt");
        expectReason(before, 0, "ELIGIBLE");
        expectReason(before, 1, "ELIGIBLE");

        redis.opsForValue().set(RedisKeys.campaign("c-corrupt"), "{not json");
        Thread.sleep(1_500); // the last good config of c-corrupt ages out; c-good keeps refreshing

        freshSpendWatermarks(redis, System.currentTimeMillis());
        var after = requestEligibility(mvc, "r-after-corruption", "photo editor", "c-good", "c-corrupt");
        expectReason(after, 0, "ELIGIBLE");
        expectReason(after, 1, "CONFIG_STALE");
    }
}
