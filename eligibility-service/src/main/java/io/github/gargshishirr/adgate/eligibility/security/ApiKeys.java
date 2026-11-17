package io.github.gargshishirr.adgate.eligibility.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin and client API keys. Only SHA-256 digests of the keys are compared, with
 * {@link MessageDigest#isEqual}, so a check takes the same time whether a key matches or not and whatever its
 * length. Each variable may hold several comma-separated keys, so callers can move to a new key before the old one
 * is removed.
 */
public final class ApiKeys {

    /** What a key may do. Admin and client keys are separate: neither works on the other's endpoints. */
    public enum Role {
        ADMIN,
        CLIENT
    }

    /** Shorter keys are refused when strong keys are required (the prod profile). */
    public static final int MIN_STRONG_KEY_LENGTH = 32;

    static final String ADMIN_VARIABLE = "ADGATE_ADMIN_KEY";
    static final String CLIENT_VARIABLE = "ADGATE_CLIENT_KEY";

    private static final Logger log = LoggerFactory.getLogger(ApiKeys.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<byte[]> adminDigests;
    private final List<byte[]> clientDigests;
    private final String firstClientKey;

    private ApiKeys(List<String> adminKeys, List<String> clientKeys) {
        this.adminDigests = adminKeys.stream().map(ApiKeys::sha256).toList();
        this.clientDigests = clientKeys.stream().map(ApiKeys::sha256).toList();
        this.firstClientKey = clientKeys.get(0);
    }

    /**
     * Validates the configured keys. Without strong keys required (local runs), a missing key is replaced by a random
     * one that is logged once, so a demo works with no setup. With strong keys required, a missing or short key stops
     * the service from starting.
     *
     * @throws InvalidApiKeysException if the keys are not safe to use
     */
    public static ApiKeys create(ApiKeyProperties properties) {
        List<String> admin = parse(properties.adminKeys());
        List<String> client = parse(properties.clientKeys());
        List<String> problems = new ArrayList<>();
        check(ADMIN_VARIABLE, admin, properties.requireStrongKeys(), problems);
        check(CLIENT_VARIABLE, client, properties.requireStrongKeys(), problems);
        if (admin.stream().anyMatch(client::contains)) {
            problems.add("the same key is in both " + ADMIN_VARIABLE + " and " + CLIENT_VARIABLE + "; admin and client keys must differ");
        }
        if (!problems.isEmpty()) {
            throw new InvalidApiKeysException(problems);
        }

        Map<String, String> generated = new LinkedHashMap<>();
        if (admin.isEmpty()) {
            admin = List.of(randomKey());
            generated.put(ADMIN_VARIABLE, admin.get(0));
        }
        if (client.isEmpty()) {
            client = List.of(randomKey());
            generated.put(CLIENT_VARIABLE, client.get(0));
        }
        if (!generated.isEmpty()) {
            logGenerated(generated);
        }
        return new ApiKeys(admin, client);
    }

    /** The role of a presented key, or null if it is not a valid key. */
    public Role roleOf(String presentedKey) {
        if (presentedKey == null || presentedKey.isEmpty()) {
            return null;
        }
        byte[] digest = sha256(presentedKey);
        boolean admin = matchesAny(digest, adminDigests);
        boolean client = matchesAny(digest, clientDigests);
        return admin ? Role.ADMIN : client ? Role.CLIENT : null;
    }

    /** Used by the in-process warm-up, which calls the decision endpoint like a real client. */
    public String firstClientKey() {
        return firstClientKey;
    }

    static List<String> parse(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(key -> !key.isEmpty()).distinct().toList();
    }

    private static void check(String variable, List<String> keys, boolean requireStrong, List<String> problems) {
        if (keys.isEmpty()) {
            if (requireStrong) {
                problems.add(variable + " is not set");
            }
            return;
        }
        if (!keys.stream().allMatch(ApiKeys::isPrintableAscii)) {
            problems.add(variable + " may only contain printable ASCII characters, without spaces");
        }
        if (keys.stream().anyMatch(key -> key.length() < MIN_STRONG_KEY_LENGTH)) {
            if (requireStrong) {
                problems.add("every key in " + variable + " must be at least " + MIN_STRONG_KEY_LENGTH + " characters");
            } else {
                log.warn("{} has a key shorter than {} characters. That is fine for a local demo, but the prod profile refuses it.",
                        variable, MIN_STRONG_KEY_LENGTH);
            }
        }
    }

    private static boolean isPrintableAscii(String key) {
        return key.chars().allMatch(c -> c > 0x20 && c < 0x7F);
    }

    private static boolean matchesAny(byte[] digest, List<byte[]> candidates) {
        boolean match = false;
        for (byte[] candidate : candidates) {
            match |= MessageDigest.isEqual(digest, candidate);
        }
        return match;
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void logGenerated(Map<String, String> generated) {
        String rule = "=".repeat(78);
        String exports = generated.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
        log.warn("""

                {}
                 Not set: {}
                 AdGate made random keys for this run (local mode only). Use them in other
                 terminals, for example for traffic-sim and curl:

                   export {}

                 New keys are made on every restart. Set your own to keep them.
                 The prod profile never makes keys: it refuses to start without them.
                {}""", rule, String.join(" ", generated.keySet()), exports, rule);
    }
}
