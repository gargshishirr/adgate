package io.github.gargshishirr.adgate.eligibility.state;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes how far behind real time each spend partition is, as seen by the serving side
 * ({@code adgate.spend.watermark.lag}, milliseconds; -1 when a partition has no watermark yet). This is the
 * metric to alert on: it rises before campaigns start failing closed with SPEND_DATA_STALE.
 */
@Component
public class SpendFreshnessMonitor {

    private final StringRedisTemplate redis;
    private final SpendPartitionCount partitionCount;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Integer, AtomicLong> lagByPartition = new ConcurrentHashMap<>();

    public SpendFreshnessMonitor(StringRedisTemplate redis, SpendPartitionCount partitionCount, MeterRegistry registry, Clock clock) {
        this.redis = redis;
        this.partitionCount = partitionCount;
        this.registry = registry;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    void refresh() {
        int partitions = partitionCount.current();
        List<String> keys = IntStream.range(0, partitions).mapToObj(RedisKeys::watermark).toList();
        List<String> watermarks;
        try {
            watermarks = redis.opsForValue().multiGet(keys);
        } catch (RuntimeException e) {
            return; // Redis is unreachable; the circuit breaker and serving metrics already report it
        }
        long now = clock.millis();
        for (int partition = 0; partition < partitions; partition++) {
            String watermark = watermarks == null ? null : watermarks.get(partition);
            long lag = watermark == null ? -1 : now - Long.parseLong(watermark);
            gauge(partition).set(lag);
        }
    }

    private AtomicLong gauge(int partition) {
        return lagByPartition.computeIfAbsent(partition, p -> {
            AtomicLong holder = new AtomicLong(-1);
            Gauge.builder("adgate.spend.watermark.lag", holder, AtomicLong::get)
                    .description("How far behind real time the spend of this partition is, in milliseconds (-1 if unknown)")
                    .baseUnit("milliseconds")
                    .tag("partition", Integer.toString(p))
                    .register(registry);
            return holder;
        });
    }
}
