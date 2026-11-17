package io.github.gargshishirr.adgate.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedisKeysTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);

    @Test
    void spendKeysAreBucketedByUtcDay() {
        assertThat(RedisKeys.spend("c-1", DAY)).isEqualTo("adgate:spend:c-1:20261031");
    }

    @Test
    void utcDayIgnoresTheLocalTimeZone() {
        long lastMilliOfDay = 1_793_491_199_999L; // 2026-10-31T23:59:59.999Z
        assertThat(RedisKeys.utcDay(lastMilliOfDay)).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(RedisKeys.utcDay(lastMilliOfDay + 1)).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"c-1", "C_2.v3", "550e8400-e29b-41d4-a716-446655440000", "7"})
    void acceptsSafeIds(String id) {
        assertThat(RedisKeys.isSafeId(id)).isTrue();
        assertThat(RedisKeys.campaign(id)).isEqualTo("adgate:campaign:" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "c:1", "c-*", "c?", "c[1]", "c 1", "c\n1", "c\r1", "c\u00001", "-c", ".c", "..", "c/1", "c\\1",
            "c{1}", "\u0441-1"})
    void rejectsIdsThatCouldChangeTheKey(String id) {
        assertThat(RedisKeys.isSafeId(id)).isFalse();
        assertThatThrownBy(() -> RedisKeys.campaign(id)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(id.isEmpty() ? "\u0000" : id);
        assertThatThrownBy(() -> RedisKeys.spend(id, DAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.spendDedupe(id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisKeys.deviceRate(id, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullAndOverlongIds() {
        assertThat(RedisKeys.isSafeId(null)).isFalse();
        assertThat(RedisKeys.isSafeId("a".repeat(RedisKeys.MAX_ID_LENGTH))).isTrue();
        assertThat(RedisKeys.isSafeId("a".repeat(RedisKeys.MAX_ID_LENGTH + 1))).isFalse();
    }

    @Test
    void theValidationPatternAgreesWithTheCheck() {
        Pattern pattern = Pattern.compile(RedisKeys.SAFE_ID_PATTERN);
        List<String> samples = List.of("c-1", "a", "Z9", "a.b_c-d", "", "-a", "_a", ".a", "a:b", "a b", "a*", "\u00e9",
                "a".repeat(128), "a".repeat(129));
        for (String sample : samples) {
            assertThat(pattern.matcher(sample).matches()).as(sample).isEqualTo(RedisKeys.isSafeId(sample));
        }
    }

    @Test
    void keysOfDifferentKindsNeverShareANamespace() {
        String day = "20261031";
        assertThat(RedisKeys.spend("dedupe", DAY)).isNotEqualTo(RedisKeys.spendDedupe(day));
        assertThat(RedisKeys.spend("wm", DAY)).doesNotStartWith("adgate:spend-wm:");
        assertThat(RedisKeys.campaign("s")).isNotEqualTo(RedisKeys.CAMPAIGN_INDEX);
    }
}
