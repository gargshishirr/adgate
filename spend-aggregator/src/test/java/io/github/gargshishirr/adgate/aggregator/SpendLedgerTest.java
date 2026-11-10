package io.github.gargshishirr.adgate.aggregator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.github.fppt.jedismock.RedisServer;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.common.SpendEvent;

class SpendLedgerTest {

    private static final long OCT_31_10AM = Instant.parse("2026-10-31T10:00:00Z").toEpochMilli();

    private static RedisServer server;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private SpendLedger ledger;

    @BeforeAll
    static void startRedis() throws IOException {
        server = RedisServer.newRedisServer().start();
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(server.getHost(), server.getBindPort()));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        connectionFactory.destroy();
        server.stop();
    }

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        ledger = new SpendLedger(redis, new AggregatorProperties(6, 1, Duration.ofDays(2), Duration.ofDays(3),
                Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    @Test
    void redeliveredBatchIsCountedOnce() {
        List<SpendEvent> batch = List.of(
                new SpendEvent("e-1", "c-1", 1_500_000, OCT_31_10AM),
                new SpendEvent("e-2", "c-1", 500_000, OCT_31_10AM));

        assertThat(ledger.apply(batch)).isEqualTo(2);
        assertThat(ledger.apply(batch)).isZero();

        assertThat(spend("c-1", LocalDate.of(2026, 10, 31))).isEqualTo("2000000");
    }

    @Test
    void duplicateInsideOneBatchIsCountedOnce() {
        SpendEvent event = new SpendEvent("e-1", "c-1", 700_000, OCT_31_10AM);

        assertThat(ledger.apply(List.of(event, event))).isEqualTo(1);
        assertThat(spend("c-1", LocalDate.of(2026, 10, 31))).isEqualTo("700000");
    }

    @Test
    void spendCountsAgainstTheUtcDayOfTheEvent() {
        long lateOct30 = Instant.parse("2026-10-30T23:59:00Z").toEpochMilli();

        ledger.apply(List.of(
                new SpendEvent("e-1", "c-1", 100, lateOct30),
                new SpendEvent("e-2", "c-1", 200, OCT_31_10AM)));

        assertThat(spend("c-1", LocalDate.of(2026, 10, 30))).isEqualTo("100");
        assertThat(spend("c-1", LocalDate.of(2026, 10, 31))).isEqualTo("200");
    }

    @Test
    void spendCountersExpire() {
        ledger.apply(List.of(new SpendEvent("e-1", "c-1", 100, OCT_31_10AM)));

        Long ttl = redis.getExpire(RedisKeys.spend("c-1", LocalDate.of(2026, 10, 31)));
        assertThat(ttl).isPositive().isLessThanOrEqualTo(Duration.ofDays(3).toSeconds());
    }

    private static String spend(String campaignId, LocalDate day) {
        return redis.opsForValue().get(RedisKeys.spend(campaignId, day));
    }
}
