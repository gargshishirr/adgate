package io.github.gargshishirr.adgate.eligibility.api;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.gargshishirr.adgate.common.SpendPartitioner;
import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.domain.PacingMode;
import io.github.gargshishirr.adgate.eligibility.engine.EngineSettings;
import io.github.gargshishirr.adgate.eligibility.engine.PacingPolicy;
import io.github.gargshishirr.adgate.eligibility.state.CampaignConfigCache;
import io.github.gargshishirr.adgate.eligibility.state.CampaignRepository;
import io.github.gargshishirr.adgate.eligibility.state.SpendPartitionCount;
import jakarta.validation.Valid;

/** Campaign management for demos and tests. In production this data would come from the campaign platform. */
@RestController
@RequestMapping("/admin/campaigns")
public class CampaignAdminController {

    private final CampaignRepository repository;
    private final CampaignConfigCache configCache;
    private final SpendPartitionCount partitionCount;
    private final EngineSettings settings;
    private final Clock clock;

    public CampaignAdminController(CampaignRepository repository, CampaignConfigCache configCache,
                                   SpendPartitionCount partitionCount, EngineSettings settings, Clock clock) {
        this.repository = repository;
        this.configCache = configCache;
        this.partitionCount = partitionCount;
        this.settings = settings;
        this.clock = clock;
    }

    @PutMapping("/{campaignId}")
    public Campaign upsert(@PathVariable String campaignId, @Valid @RequestBody CampaignUpsertRequest request) {
        Campaign campaign = request.toCampaign(campaignId);
        repository.save(campaign);
        configCache.invalidate(campaignId);
        return campaign;
    }

    @GetMapping("/{campaignId}")
    public ResponseEntity<CampaignView> get(@PathVariable String campaignId) {
        Optional<Campaign> campaign = repository.find(campaignId);
        return ResponseEntity.of(campaign.map(this::view));
    }

    @GetMapping
    public List<CampaignView> list() {
        return repository.findAll(repository.listIds()).values().stream()
                .flatMap(Optional::stream)
                .map(this::view)
                .toList();
    }

    private CampaignView view(Campaign campaign) {
        long now = clock.millis();
        long spent = repository.spentMicros(campaign.id(), now);
        long budget = campaign.dailyBudgetMicros();
        Double allowancePercent = null;
        if (campaign.pacing() == PacingMode.EVEN) {
            long allowance = PacingPolicy.evenPacingAllowanceMicros(budget, PacingPolicy.dayFraction(now),
                    settings.pacingBurst(), settings.pacingEarlyDayAllowance());
            allowancePercent = round(100.0 * allowance / budget);
        }
        int partition = SpendPartitioner.partitionFor(campaign.id(), partitionCount.current());
        Long spendLag = repository.spendWatermark(partition).map(watermark -> now - watermark).orElse(null);
        return new CampaignView(campaign, spent, round(100.0 * spent / budget), allowancePercent, partition, spendLag);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
