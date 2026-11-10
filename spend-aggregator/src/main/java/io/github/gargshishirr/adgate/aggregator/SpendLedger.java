package io.github.gargshishirr.adgate.aggregator;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.common.SpendEvent;

/**
 * Idempotent daily spend ledger in Redis. A whole Kafka batch is applied in one atomic Lua call:
 * one network round trip, and an event id that was already applied is never counted twice, so
 * at-least-once delivery from Kafka becomes effectively-once spend accounting.
 */
@Component
public class SpendLedger {

    private static final RedisScript<Long> APPLY_BATCH =
            RedisScript.of(new ClassPathResource("lua/apply_spend_batch.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final AggregatorProperties properties;

    public SpendLedger(StringRedisTemplate redis, AggregatorProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** Applies the events and returns how many were new (the rest were duplicates). */
    public long apply(List<SpendEvent> events) {
        if (events.isEmpty()) {
            return 0;
        }
        List<String> keys = new ArrayList<>(events.size() * 2);
        List<String> args = new ArrayList<>(events.size() + 2);
        args.add(Long.toString(properties.dedupeTtl().toSeconds()));
        args.add(Long.toString(properties.spendTtl().toSeconds()));
        for (SpendEvent event : events) {
            keys.add(RedisKeys.spendDedupe(event.eventId()));
            keys.add(RedisKeys.spend(event.campaignId(), RedisKeys.utcDay(event.eventTimeMillis())));
            args.add(Long.toString(event.costMicros()));
        }
        Long applied = redis.execute(APPLY_BATCH, keys, args.toArray());
        return applied == null ? 0 : applied;
    }
}
