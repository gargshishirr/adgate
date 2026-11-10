package io.github.gargshishirr.adgate.eligibility.state;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.common.SpendPartitioner;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest;
import io.github.gargshishirr.adgate.eligibility.domain.EligibilityRequest.Candidate;
import io.github.gargshishirr.adgate.eligibility.engine.CampaignState;
import io.github.gargshishirr.adgate.eligibility.engine.ConfigStatus;
import io.github.gargshishirr.adgate.eligibility.engine.EngineSettings;
import io.github.gargshishirr.adgate.eligibility.engine.ServingState;

/** Assembles the {@link ServingState} snapshot for a request: cached configs plus one Redis round trip. */
@Component
public class ServingStateLoader {

    private final CampaignConfigCache configs;
    private final RedisStateReader reader;
    private final SpendPartitionCount partitionCount;
    private final EngineSettings settings;
    private final Clock clock;

    public ServingStateLoader(CampaignConfigCache configs, RedisStateReader reader, SpendPartitionCount partitionCount,
                              EngineSettings settings, Clock clock) {
        this.configs = configs;
        this.reader = reader;
        this.partitionCount = partitionCount;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @param countRequest whether this request counts toward the device's traffic-quality window; false for
     *                     "what if" re-evaluations that must not have side effects
     */
    public ServingState load(EligibilityRequest request, boolean countRequest) {
        long now = clock.millis();
        List<String> campaignIds = request.candidates().stream().map(Candidate::campaignId).distinct().toList();
        Map<String, CampaignConfigCache.Entry> configEntries = configs.getAll(campaignIds);

        int partitions = partitionCount.current();
        LocalDate day = RedisKeys.utcDay(now);
        List<String> spendKeys = new ArrayList<>(campaignIds.size());
        List<String> watermarkKeys = new ArrayList<>(campaignIds.size());
        int[] partitionOf = new int[campaignIds.size()];
        for (int i = 0; i < campaignIds.size(); i++) {
            String campaignId = campaignIds.get(i);
            partitionOf[i] = SpendPartitioner.partitionFor(campaignId, partitions);
            spendKeys.add(RedisKeys.spend(campaignId, day));
            watermarkKeys.add(RedisKeys.watermark(partitionOf[i]));
        }
        long windowSeconds = Math.max(1, settings.deviceRateWindow().toSeconds());
        long windowStart = Math.floorDiv(now / 1000, windowSeconds) * windowSeconds;
        String deviceRateKey = RedisKeys.deviceRate(request.device().id(), windowStart);

        RedisStateReader.Result result = reader.read(spendKeys, watermarkKeys, deviceRateKey, windowSeconds, countRequest);

        Map<String, CampaignState> campaigns = new LinkedHashMap<>();
        for (int i = 0; i < campaignIds.size(); i++) {
            String campaignId = campaignIds.get(i);
            CampaignConfigCache.Entry entry = configEntries.getOrDefault(campaignId,
                    new CampaignConfigCache.Entry(ConfigStatus.UNAVAILABLE, null, now));
            campaigns.put(campaignId, new CampaignState(
                    campaignId,
                    entry.status(),
                    entry.campaign(),
                    entry.loadedAtMillis(),
                    result.ok() ? result.spentMicros().get(i) : 0,
                    result.ok() ? result.watermarks().get(i) : null,
                    partitionOf[i]));
        }
        return new ServingState(now, result.status(), result.detail(), result.deviceRequests(), campaigns);
    }
}
