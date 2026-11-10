package io.github.gargshishirr.adgate.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class RedisKeysTest {

    @Test
    void spendKeysAreBucketedByUtcDay() {
        assertThat(RedisKeys.spend("c-1", LocalDate.of(2026, 10, 31))).isEqualTo("adgate:spend:c-1:20261031");
    }

    @Test
    void utcDayIgnoresTheLocalTimeZone() {
        long lastMilliOfDay = 1_793_491_199_999L; // 2026-10-31T23:59:59.999Z
        assertThat(RedisKeys.utcDay(lastMilliOfDay)).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(RedisKeys.utcDay(lastMilliOfDay + 1)).isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
