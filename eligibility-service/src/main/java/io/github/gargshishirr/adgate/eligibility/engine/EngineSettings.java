package io.github.gargshishirr.adgate.eligibility.engine;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables of the eligibility engine.
 *
 * @param maxSpendStaleness        decline (fail closed) when a campaign's spend watermark is older than this
 * @param maxConfigAge             decline (fail closed) when a campaign's configuration could not be refreshed for this long
 * @param deviceRequestLimit       requests one device may make per window before its traffic is treated as invalid
 * @param deviceRateWindow         length of the fixed traffic-quality window
 * @param pacingBurst              how far ahead of the even-pacing line a campaign may run, as a fraction of the schedule
 * @param pacingEarlyDayAllowance  share of the daily budget available right after midnight, so delivery can start
 * @param pacingRampFraction       share of the budget below the pacing line where serving is thinned out gradually
 * @param budgetSafetyMargin       share of the budget held back for spend that is still in flight through the pipeline
 * @param blockedUserAgentTokens   case-insensitive substrings that mark a user agent as automated
 */
@ConfigurationProperties("adgate.engine")
public record EngineSettings(
        @DefaultValue("10s") Duration maxSpendStaleness,
        @DefaultValue("30s") Duration maxConfigAge,
        @DefaultValue("20") long deviceRequestLimit,
        @DefaultValue("10s") Duration deviceRateWindow,
        @DefaultValue("0.10") double pacingBurst,
        @DefaultValue("0.02") double pacingEarlyDayAllowance,
        @DefaultValue("0.02") double pacingRampFraction,
        @DefaultValue("0.005") double budgetSafetyMargin,
        @DefaultValue({"bot", "crawler", "spider", "headless", "python-requests"}) List<String> blockedUserAgentTokens) {

    public EngineSettings {
        blockedUserAgentTokens = List.copyOf(blockedUserAgentTokens);
    }

    public static EngineSettings defaults() {
        return new EngineSettings(Duration.ofSeconds(10), Duration.ofSeconds(30), 20, Duration.ofSeconds(10),
                0.10, 0.02, 0.02, 0.005, List.of("bot", "crawler", "spider", "headless", "python-requests"));
    }
}
