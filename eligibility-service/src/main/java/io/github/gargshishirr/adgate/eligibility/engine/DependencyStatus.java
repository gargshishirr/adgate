package io.github.gargshishirr.adgate.eligibility.engine;

/** Whether the shared serving state could be read for this request. */
public enum DependencyStatus {
    OK,
    /** Redis returned an error or timed out. */
    UNAVAILABLE,
    /** The circuit breaker is open; Redis was not called at all. */
    CIRCUIT_OPEN,
    /** State was read, but the request already used up its latency budget. */
    DEADLINE_EXCEEDED
}
