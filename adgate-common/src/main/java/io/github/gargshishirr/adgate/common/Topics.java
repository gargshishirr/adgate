package io.github.gargshishirr.adgate.common;

/** Kafka topic names shared by producers and consumers. */
public final class Topics {

    /** Billable spend events, keyed by campaign id. */
    public static final String SPEND_EVENTS = "adgate.spend-events";

    /** One structured record per eligibility decision, for funnel analytics and offline replay. */
    public static final String ELIGIBILITY_DECISIONS = "adgate.eligibility-decisions";

    private Topics() {
    }
}
