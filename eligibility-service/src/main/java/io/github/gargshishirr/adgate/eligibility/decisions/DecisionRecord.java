package io.github.gargshishirr.adgate.eligibility.decisions;

import java.util.List;

import io.github.gargshishirr.adgate.eligibility.domain.Decision;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.engine.ServingState;

/** Everything needed to explain or replay one decision: the request, the state snapshot and the verdicts. */
public record DecisionRecord(EligibilityRequest request, ServingState state, List<Decision> decisions, long latencyMicros) {
}
