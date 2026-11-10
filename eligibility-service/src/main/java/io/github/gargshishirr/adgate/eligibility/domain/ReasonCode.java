package io.github.gargshishirr.adgate.eligibility.domain;

/**
 * Why a candidate may or may not serve. Exactly one code is emitted per candidate: the first check that
 * fails, in the order listed here. Downstream funnel analytics can therefore attribute every declined
 * impression to a single cause.
 *
 * <p>Codes marked fail-closed mean "we could not prove it is safe to serve", as opposed to "we proved it is not".
 */
public enum ReasonCode {

    ELIGIBLE(false, "Candidate passed every check and may serve"),
    DEPENDENCY_UNAVAILABLE(true, "Serving state could not be read (Redis error, timeout or open circuit)"),
    DEADLINE_EXCEEDED(true, "The latency budget ran out before serving state was read"),
    TRAFFIC_INVALID_USER_AGENT(false, "The request's user agent matches a known automation pattern"),
    TRAFFIC_RATE_EXCEEDED(false, "The device exceeded the request rate allowed in the traffic-quality window"),
    CAMPAIGN_NOT_FOUND(false, "No configuration exists for the campaign"),
    CONFIG_STALE(true, "The campaign configuration could not be refreshed within its allowed age"),
    CAMPAIGN_PAUSED(false, "The campaign is paused"),
    POLICY_PLACEMENT_NOT_ALLOWED(false, "The campaign may not serve on this placement"),
    POLICY_NEGATIVE_KEYWORD(false, "The search query matches one of the campaign's negative keywords"),
    SPEND_DATA_STALE(true, "Spend for the campaign's partition is older than the allowed staleness"),
    BUDGET_EXHAUSTED(false, "The campaign has spent its daily budget"),
    PACING_THROTTLED(false, "The campaign is ahead of its even-pacing schedule");

    private final boolean failClosed;
    private final String description;

    ReasonCode(boolean failClosed, String description) {
        this.failClosed = failClosed;
        this.description = description;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public String description() {
        return description;
    }
}
