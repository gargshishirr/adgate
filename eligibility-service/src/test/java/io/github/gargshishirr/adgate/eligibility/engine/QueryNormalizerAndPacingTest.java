package io.github.gargshishirr.adgate.eligibility.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QueryNormalizerAndPacingTest {

    @Test
    void foldsCaseAccentsAndFullWidthCharacters() {
        assertThat(QueryNormalizer.tokens("ＦＲＥＥ Café, Photo-Editor!")).containsExactly("free", "cafe", "photo", "editor");
        assertThat(QueryNormalizer.tokens("   ")).isEmpty();
        assertThat(QueryNormalizer.tokens(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"games,game", "stories,story", "boxes,box", "matches,match", "glass,glass", "bus,bus", "news,new", "is,is"})
    void stemsPluralsLightly(String token, String stem) {
        assertThat(QueryNormalizer.stem(token)).isEqualTo(stem);
    }

    @Test
    void phrasesMatchOnlyAsContiguousTokens() {
        List<String> query = List.of("cheap", "fun", "game");

        assertThat(QueryNormalizer.containsPhrase(query, List.of("fun", "game"))).isTrue();
        assertThat(QueryNormalizer.containsPhrase(query, List.of("cheap", "game"))).isFalse();
        assertThat(QueryNormalizer.containsPhrase(query, List.of())).isFalse();
    }

    @Test
    void dayFractionFollowsTheUtcClock() {
        assertThat(PacingPolicy.dayFraction(Instant.parse("2026-10-31T00:00:00Z").toEpochMilli())).isZero();
        assertThat(PacingPolicy.dayFraction(Instant.parse("2026-10-31T18:00:00Z").toEpochMilli())).isEqualTo(0.75);
    }

    @Test
    void evenPacingAllowanceStartsAboveZeroAndNeverExceedsTheBudget() {
        assertThat(PacingPolicy.evenPacingAllowanceMicros(100_000_000, 0.0, 0.10, 0.02)).isEqualTo(2_000_000);
        assertThat(PacingPolicy.evenPacingAllowanceMicros(100_000_000, 0.5, 0.10, 0.02)).isBetween(56_999_999L, 57_000_000L);
        assertThat(PacingPolicy.evenPacingAllowanceMicros(100_000_000, 0.99, 0.10, 0.02)).isEqualTo(100_000_000);
    }
}
