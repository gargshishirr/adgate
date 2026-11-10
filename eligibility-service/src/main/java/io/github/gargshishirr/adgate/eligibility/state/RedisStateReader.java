package io.github.gargshishirr.adgate.eligibility.state;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.eligibility.engine.DependencyStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Reads spend counters, partition watermarks and the device request count in one pipelined round trip, behind a
 * circuit breaker. Spend and watermarks come from a single MGET, so they are an atomic snapshot: the spend we see
 * is never older than the watermark we see. Failures are returned as a status rather than thrown; the engine turns
 * them into fail-closed decisions.
 */
@Component
public class RedisStateReader {

    /** Values are aligned with the spend keys passed to {@link #read}; watermarks are null when unknown. */
    public record Result(DependencyStatus status, String detail, List<Long> spentMicros, List<Long> watermarks, long deviceRequests) {

        static Result failed(DependencyStatus status, String detail) {
            return new Result(status, detail, List.of(), List.of(), 0);
        }

        public boolean ok() {
            return status == DependencyStatus.OK;
        }
    }

    private final StringRedisTemplate redis;
    private final CircuitBreaker breaker;

    public RedisStateReader(StringRedisTemplate redis, CircuitBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    /**
     * @param countRequest true to count this request in the device's traffic-quality window (INCR), false to only read it
     */
    public Result read(List<String> spendKeys, List<String> watermarkKeys, String deviceRateKey, long rateWindowSeconds, boolean countRequest) {
        int campaigns = spendKeys.size();
        String[] snapshotKeys = new String[campaigns * 2];
        for (int i = 0; i < campaigns; i++) {
            snapshotKeys[i] = spendKeys.get(i);
            snapshotKeys[campaigns + i] = watermarkKeys.get(i);
        }
        try {
            List<Object> replies = breaker.executeSupplier(() -> redis.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection commands = (StringRedisConnection) connection;
                commands.mGet(snapshotKeys);
                if (countRequest) {
                    commands.incr(deviceRateKey);
                    commands.expire(deviceRateKey, rateWindowSeconds * 2);
                } else {
                    commands.get(deviceRateKey);
                }
                return null;
            }));
            return parse(replies, campaigns);
        } catch (CallNotPermittedException e) {
            return Result.failed(DependencyStatus.CIRCUIT_OPEN, "circuit breaker '" + breaker.getName() + "' is open");
        } catch (RuntimeException e) {
            return Result.failed(DependencyStatus.UNAVAILABLE, rootCause(e));
        }
    }

    private static Result parse(List<Object> replies, int campaigns) {
        if (replies == null || replies.size() < 2 || !(replies.get(0) instanceof List<?> values) || values.size() != 2 * campaigns) {
            return Result.failed(DependencyStatus.UNAVAILABLE, "unexpected reply to the serving-state pipeline");
        }
        List<Long> spent = new ArrayList<>(campaigns);
        List<Long> watermarks = new ArrayList<>(campaigns);
        for (int i = 0; i < campaigns; i++) {
            Long value = toLong(values.get(i));
            spent.add(value == null ? 0L : value);
            watermarks.add(toLong(values.get(campaigns + i)));
        }
        Long deviceRequests = toLong(replies.get(1));
        return new Result(DependencyStatus.OK, "", spent, watermarks, deviceRequests == null ? 0 : deviceRequests);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        return text.isEmpty() ? null : Long.valueOf(text);
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
