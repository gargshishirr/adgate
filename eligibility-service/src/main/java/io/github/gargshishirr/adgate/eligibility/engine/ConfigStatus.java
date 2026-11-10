package io.github.gargshishirr.adgate.eligibility.engine;

public enum ConfigStatus {
    FOUND,
    NOT_FOUND,
    /** The configuration could not be loaded (and was not cached). */
    UNAVAILABLE
}
