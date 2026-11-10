package io.github.gargshishirr.adgate.eligibility.engine;

/**
 * Deterministic "randomness" for probabilistic throttling: the draw depends only on the request and campaign,
 * so replaying a decision makes exactly the same choice, and retries of the same request are consistent.
 */
public final class DeterministicSampler {

    private DeterministicSampler() {
    }

    /** A value in [0, 1) that is uniformly distributed across request ids. */
    public static double unit(String requestId, String campaignId) {
        long seed = ((long) requestId.hashCode() << 32) ^ (campaignId.hashCode() & 0xFFFF_FFFFL);
        return (mix(seed) >>> 11) * 0x1.0p-53;
    }

    /** SplitMix64 finalizer: spreads similar seeds across the whole 64-bit range. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
