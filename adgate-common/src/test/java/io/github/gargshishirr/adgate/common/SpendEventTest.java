package io.github.gargshishirr.adgate.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class SpendEventTest {

    private static final long NOW = 1_793_446_470_120L;

    @Test
    void acceptsANormalCharge() {
        assertThat(new SpendEvent("e-1", "c-1", 250_000, NOW).isValid()).isTrue();
    }

    @Test
    void rejectsIdsThatAreNotSafeInRedisKeys() {
        assertThat(new SpendEvent("e:1", "c-1", 250_000, NOW).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-1:20260101", 250_000, NOW).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-*", 250_000, NOW).isValid()).isFalse();
        assertThat(new SpendEvent(null, "c-1", 250_000, NOW).isValid()).isFalse();
    }

    @Test
    void rejectsCostsThatCouldOverflowTheDailyCounter() {
        assertThat(new SpendEvent("e-1", "c-1", 0, NOW).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-1", SpendEvent.MAX_COST_MICROS, NOW).isValid()).isTrue();
        assertThat(new SpendEvent("e-1", "c-1", SpendEvent.MAX_COST_MICROS + 1, NOW).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-1", Long.MAX_VALUE, NOW).isValid()).isFalse();
    }

    @Test
    void acceptsOnlyTimesThatMapToADailyKey() {
        long last = SpendEvent.MAX_EVENT_TIME_MILLIS - 1;
        assertThat(new SpendEvent("e-1", "c-1", 1, last).isValid()).isTrue();
        assertThatCode(() -> RedisKeys.spend("c-1", RedisKeys.utcDay(last))).doesNotThrowAnyException();

        assertThat(new SpendEvent("e-1", "c-1", 1, SpendEvent.MAX_EVENT_TIME_MILLIS).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-1", 1, Long.MAX_VALUE).isValid()).isFalse();
        assertThat(new SpendEvent("e-1", "c-1", 1, 0).isValid()).isFalse();
    }
}
