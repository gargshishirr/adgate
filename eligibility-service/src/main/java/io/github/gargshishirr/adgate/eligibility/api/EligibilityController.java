package io.github.gargshishirr.adgate.eligibility.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.gargshishirr.adgate.eligibility.EligibilityService;
import io.github.gargshishirr.adgate.eligibility.ReplayResult;
import io.github.gargshishirr.adgate.eligibility.decisions.DecisionRecord;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1")
public class EligibilityController {

    private final EligibilityService service;

    public EligibilityController(EligibilityService service) {
        this.service = service;
    }

    /** Called inline by the ad server for every ad request. */
    @PostMapping("/eligibility")
    public EligibilityResponse evaluate(@Valid @RequestBody EligibilityRequest request) {
        return service.evaluate(request);
    }

    /** On-call tooling: the request, the exact state snapshot and the decisions, for a recent request id. */
    @GetMapping("/decisions/{requestId}")
    public ResponseEntity<DecisionRecord> explain(@PathVariable String requestId) {
        return ResponseEntity.of(service.explain(requestId));
    }

    /** On-call tooling: proves a decision is reproducible, and optionally shows what would happen now. */
    @PostMapping("/decisions/{requestId}/replay")
    public ResponseEntity<ReplayResult> replay(@PathVariable String requestId,
                                               @RequestParam(defaultValue = "false") boolean currentState) {
        return ResponseEntity.of(service.replay(requestId, currentState));
    }
}
