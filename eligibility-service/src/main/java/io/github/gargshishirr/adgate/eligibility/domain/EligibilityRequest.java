package io.github.gargshishirr.adgate.eligibility.domain;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One ad request from the ad server, with the candidates that survived retrieval. */
public record EligibilityRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull Placement placement,
        @Size(max = 512) String query,
        @NotNull @Valid Device device,
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Candidate> candidates) {

    public record Device(@NotBlank @Size(max = 128) String id, @Size(max = 512) String userAgent) {
    }

    public record Candidate(@NotBlank @Size(max = 128) String adId, @NotBlank @Size(max = 128) String campaignId) {
    }
}
