package io.github.gargshishirr.adgate.aggregator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.common.RedisKeys;

/** Publishes per-partition spend watermarks to Redis with max-wins semantics. */
@Component
public class WatermarkStore {

    private static final RedisScript<Long> ADVANCE =
            RedisScript.of(new ClassPathResource("lua/advance_watermarks.lua"), Long.class);

    private final StringRedisTemplate redis;

    public WatermarkStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void advance(Map<Integer, Long> watermarksByPartition) {
        if (watermarksByPartition.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(watermarksByPartition.size());
        List<String> args = new ArrayList<>(watermarksByPartition.size());
        watermarksByPartition.forEach((partition, watermark) -> {
            keys.add(RedisKeys.watermark(partition));
            args.add(Long.toString(watermark));
        });
        redis.execute(ADVANCE, keys, args.toArray());
    }
}
