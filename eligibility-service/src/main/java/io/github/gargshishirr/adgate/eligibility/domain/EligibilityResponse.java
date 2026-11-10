package io.github.gargshishirr.adgate.eligibility.domain;

import java.util.List;

public record EligibilityResponse(String requestId, long evaluatedAtMillis, long latencyMicros, List<Decision> decisions) {
}
