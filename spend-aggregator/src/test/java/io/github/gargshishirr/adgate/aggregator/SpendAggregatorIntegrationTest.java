package io.github.gargshishirr.adgate.aggregator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.common.SpendEvent;
import io.github.gargshishirr.adgate.common.SpendPartitioner;
import io.github.gargshishirr.adgate.common.Topics;

/** Runs the aggregator against an in-process Kafka broker and an in-process Redis server. No Docker needed. */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.concurrency=1"
})
@EmbeddedKafka(kraft = true, partitions = 6)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpendAggregatorIntegrationTest {

    private static final RedisServer REDIS = startRedis();

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getBindPort);
    }

    /** Stop consuming before Redis goes away, so the listener does not race the shutdown. */
    @AfterAll
    void stopListenersThenRedis() throws IOException {
        listeners.stop();
        REDIS.stop();
    }

    @Test
    void appliesEachChargeOnceIntoItsDailyBucketAndPublishesTheWatermark() throws Exception {
        long now = System.currentTimeMillis();
        long yesterday = now - Duration.ofDays(1).toMillis();

        send(new SpendEvent("e-1", "c-1", 1_000_000, now));
        send(new SpendEvent("e-1", "c-1", 1_000_000, now)); // redelivered duplicate
        send(new SpendEvent("e-2", "c-1", 250_000, now));
        send(new SpendEvent("e-3", "c-1", 400_000, yesterday));
        kafka.send(Topics.SPEND_EVENTS, "c-1", "{not json").get(); // poison message is skipped, not retried forever

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(redis.opsForValue().get(RedisKeys.spend("c-1", RedisKeys.utcDay(now)))).isEqualTo("1250000");
            assertThat(redis.opsForValue().get(RedisKeys.spend("c-1", RedisKeys.utcDay(yesterday)))).isEqualTo("400000");
        });

        int partition = SpendPartitioner.partitionFor("c-1", 6);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String watermark = redis.opsForValue().get(RedisKeys.watermark(partition));
            assertThat(watermark).isNotNull();
            assertThat(Long.parseLong(watermark)).isGreaterThanOrEqualTo(now);
        });
    }

    private void send(SpendEvent event) throws Exception {
        kafka.send(Topics.SPEND_EVENTS, event.campaignId(), mapper.writeValueAsString(event)).get();
    }

    private static RedisServer startRedis() {
        try {
            return RedisServer.newRedisServer().start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
