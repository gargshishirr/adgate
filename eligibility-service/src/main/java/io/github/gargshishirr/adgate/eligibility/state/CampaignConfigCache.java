package io.github.gargshishirr.adgate.eligibility.state;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;
import io.github.gargshishirr.adgate.eligibility.domain.Campaign;
import io.github.gargshishirr.adgate.eligibility.engine.ConfigStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * Local cache of campaign configurations, so the serving path rarely touches Redis for config.
 *
 * <p>Every cached configuration is reloaded in the background on a fixed schedule, with one MGET, whether or not
 * the campaign is receiving traffic. A campaign that was idle for a while is therefore still fresh on its next
 * request. If reloading fails, entries keep their old load time, so the engine can tell how stale they are and
 * fail closed once they pass the allowed age.
 */
@Component
public class CampaignConfigCache {

    private static final Logger log = LoggerFactory.getLogger(CampaignConfigCache.class);

    /** A cached configuration and when it was loaded from Redis. */
    public record Entry(ConfigStatus status, Campaign campaign, long loadedAtMillis) {
    }

    private final CampaignRepository repository;
    private final CircuitBreaker redisBreaker;
    private final Clock clock;
    private final LoadingCache<String, Entry> cache;
    private final Set<String> corruptCampaigns = ConcurrentHashMap.newKeySet();

    public CampaignConfigCache(CampaignRepository repository, CircuitBreaker redisBreaker, AdgateProperties properties,
                               Clock clock, MeterRegistry registry, TaskScheduler scheduler) {
        this.repository = repository;
        this.redisBreaker = redisBreaker;
        this.clock = clock;
        this.cache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(1))
                .recordStats()
                .build(new CacheLoader<String, Entry>() {
                    @Override
                    public Entry load(String campaignId) {
                        return loadFromRedis(Set.of(campaignId)).get(campaignId);
                    }

                    @Override
                    public Map<String, Entry> loadAll(Set<? extends String> campaignIds) {
                        return loadFromRedis(campaignIds);
                    }
                });
        CaffeineCacheMetrics.monitor(registry, cache, "adgate.campaign.config");
        Duration refresh = properties.configRefresh();
        scheduler.scheduleWithFixedDelay(this::refreshAll, Instant.now().plus(refresh), refresh);
    }

    /** Returns an entry for every id; ids that could not be loaded are marked UNAVAILABLE. */
    public Map<String, Entry> getAll(Collection<String> campaignIds) {
        try {
            return cache.getAll(campaignIds);
        } catch (RuntimeException e) {
            Map<String, Entry> result = new HashMap<>(cache.getAllPresent(campaignIds));
            long now = clock.millis();
            for (String campaignId : campaignIds) {
                result.putIfAbsent(campaignId, new Entry(ConfigStatus.UNAVAILABLE, null, now));
            }
            return result;
        }
    }

    public void invalidate(String campaignId) {
        cache.invalidate(campaignId);
    }

    /** Reloads every cached configuration in one round trip. On failure the cached entries are kept as they are. */
    void refreshAll() {
        Set<String> campaignIds = Set.copyOf(cache.asMap().keySet());
        if (campaignIds.isEmpty()) {
            return;
        }
        try {
            cache.putAll(loadFromRedis(campaignIds));
        } catch (RuntimeException e) {
            log.debug("Campaign configuration refresh failed; cached entries keep aging: {}", e.toString());
        }
    }

    /**
     * Loads the given campaigns in one round trip. A corrupt configuration only affects its own campaign: it keeps
     * its last good entry (which then ages toward CONFIG_STALE), or is UNAVAILABLE if there is none.
     */
    private Map<String, Entry> loadFromRedis(Set<? extends String> campaignIds) {
        Map<String, String> stored = redisBreaker.executeSupplier(() -> repository.findAllJson(campaignIds));
        long now = clock.millis();
        Map<String, Entry> entries = new HashMap<>();
        stored.forEach((campaignId, json) -> entries.put(campaignId, toEntry(campaignId, json, now)));
        return entries;
    }

    private Entry toEntry(String campaignId, String json, long now) {
        if (json == null) {
            return new Entry(ConfigStatus.NOT_FOUND, null, now);
        }
        try {
            Entry entry = new Entry(ConfigStatus.FOUND, repository.parse(json), now);
            if (corruptCampaigns.remove(campaignId)) {
                log.info("Campaign {} has a valid configuration again", campaignId);
            }
            return entry;
        } catch (IllegalStateException e) {
            if (corruptCampaigns.add(campaignId)) {
                log.error("Campaign {} has a corrupt configuration; keeping its last good one: {}", campaignId, e.getMessage());
            }
            Entry lastGood = cache.getIfPresent(campaignId);
            return lastGood != null ? lastGood : new Entry(ConfigStatus.UNAVAILABLE, null, now);
        }
    }
}
