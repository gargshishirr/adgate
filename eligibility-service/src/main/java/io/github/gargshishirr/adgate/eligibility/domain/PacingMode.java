package io.github.gargshishirr.adgate.eligibility.domain;

public enum PacingMode {
    /** Spread the daily budget across the day. */
    EVEN,
    /** Spend as fast as traffic allows until the budget is gone. */
    ASAP
}
