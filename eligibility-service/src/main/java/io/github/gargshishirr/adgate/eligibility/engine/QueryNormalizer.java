package io.github.gargshishirr.adgate.eligibility.engine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Query understanding used by negative-keyword policy: Unicode compatibility folding, accent stripping,
 * case folding, tokenization on non-alphanumerics, and light plural stemming. Queries and keywords go through
 * the same pipeline, so "FREE Gamés!" matches the negative keyword "free game".
 */
public final class QueryNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    private QueryNormalizer() {
    }

    public static List<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD);
        folded = COMBINING_MARKS.matcher(folded).replaceAll("").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : SEPARATORS.split(folded)) {
            if (!token.isEmpty()) {
                tokens.add(stem(token));
            }
        }
        return List.copyOf(tokens);
    }

    /** True if {@code phrase} occurs in {@code tokens} as a contiguous token sequence. */
    public static boolean containsPhrase(List<String> tokens, List<String> phrase) {
        if (phrase.isEmpty() || phrase.size() > tokens.size()) {
            return false;
        }
        for (int start = 0; start + phrase.size() <= tokens.size(); start++) {
            if (tokens.subList(start, start + phrase.size()).equals(phrase)) {
                return true;
            }
        }
        return false;
    }

    static String stem(String token) {
        int length = token.length();
        if (length > 4 && token.endsWith("ies")) {
            return token.substring(0, length - 3) + "y";
        }
        if (length > 4 && token.endsWith("es")) {
            String base = token.substring(0, length - 2);
            if (base.endsWith("s") || base.endsWith("x") || base.endsWith("z") || base.endsWith("ch") || base.endsWith("sh")) {
                return base;
            }
        }
        if (length > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, length - 1);
        }
        return token;
    }
}
