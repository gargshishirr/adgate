package io.github.gargshishirr.adgate.eligibility;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.gargshishirr.adgate.eligibility.domain.Decision;

/**
 * @param reproducible true if replaying the stored snapshot produced exactly the original decisions
 * @param current      the same request evaluated against current state, when requested
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayResult(String requestId, boolean reproducible, List<Decision> original, List<Decision> replayed, List<Decision> current) {
}
