package io.github.gargshishirr.adgate.eligibility.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.common.RedisKeys;
import io.github.gargshishirr.adgate.eligibility.domain.Campaign;

/** Campaign configurations and spend counters in Redis. */
@Component
public class CampaignRepository {

    private static final Logger log = LoggerFactory.getLogger(CampaignRepository.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public CampaignRepository(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** Loads several configurations in one round trip (MGET). Missing campaigns map to null; JSON is not parsed. */
    public Map<String, String> findAllJson(Collection<? extends String> campaignIds) {
        List<String> ids = new ArrayList<>(campaignIds);
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(RedisKeys::campaign).toList());
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            result.put(ids.get(i), values == null ? null : values.get(i));
        }
        return result;
    }

    /** Loads and parses several configurations; missing or corrupt campaigns map to an empty Optional. */
    public Map<String, Optional<Campaign>> findAll(Collection<? extends String> campaignIds) {
        Map<String, Optional<Campaign>> result = new LinkedHashMap<>();
        findAllJson(campaignIds).forEach((id, json) -> {
            Optional<Campaign> campaign = Optional.empty();
            if (json != null) {
                try {
                    campaign = Optional.of(parse(json));
                } catch (IllegalStateException e) {
                        log.warn("Skipping corrupt configuration of campaign {}: {}", id, e.getMessage());
                }
            }
            result.put(id, campaign);
        });
        return result;
    }

    public Optional<Campaign> find(String campaignId) {
        return findAll(List.of(campaignId)).get(campaignId);
    }

    public void save(Campaign campaign) {
        redis.opsForValue().set(RedisKeys.campaign(campaign.id()), write(campaign));
        redis.opsForSet().add(RedisKeys.CAMPAIGN_INDEX, campaign.id());
    }

    public Set<String> listIds() {
        Set<String> ids = redis.opsForSet().members(RedisKeys.CAMPAIGN_INDEX);
        return ids == null ? Set.of() : new TreeSet<>(ids);
    }

    public long spentMicros(String campaignId, long nowMillis) {
        String value = redis.opsForValue().get(RedisKeys.spend(campaignId, RedisKeys.utcDay(nowMillis)));
        return value == null ? 0 : Long.parseLong(value);
    }

    public Optional<Long> spendWatermark(int partition) {
        return Optional.ofNullable(redis.opsForValue().get(RedisKeys.watermark(partition))).map(Long::valueOf);
    }

    /** Parses a stored configuration. Throws IllegalStateException if it is corrupt. */
    public Campaign parse(String json) {
        try {
            return mapper.readValue(json, Campaign.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt campaign configuration: " + e.getOriginalMessage(), e);
        }
    }

    private String write(Campaign campaign) {
        try {
            return mapper.writeValueAsString(campaign);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
