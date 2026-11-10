package io.github.gargshishirr.adgate.eligibility.engine;

import java.util.Map;

/**
 * A complete snapshot of the inputs to one eligibility decision. The engine is a pure function of
 * (request, snapshot), so storing the snapshot is enough to replay the decision later.
 *
 * @param nowMillis              decision time; the engine never reads the clock itself
 * @param deviceRequestsInWindow requests from this device in the current traffic-quality window, this one included
 */
public record ServingState(
        long nowMillis,
        DependencyStatus dependencyStatus,
        String dependencyDetail,
        long deviceRequestsInWindow,
        Map<String, CampaignState> campaigns) {

    public ServingState {
        campaigns = Map.copyOf(campaigns);
    }

    public ServingState withDependencyStatus(DependencyStatus status, String detail) {
        return new ServingState(nowMillis, status, detail, deviceRequestsInWindow, campaigns);
    }
}
