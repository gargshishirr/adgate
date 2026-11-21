import { evaluate } from '../engine/engine';
import { applyLatencyBudget } from '../engine/latencyBudget';
import { partitionFor } from '../engine/partitioner';
import { DEFAULT_SETTINGS, LATENCY_BUDGET_MILLIS } from '../engine/settings';
import type {
  Campaign,
  CampaignState,
  ConfigStatus,
  Decision,
  DependencyStatus,
  EligibilityRequest,
  ServingState,
} from '../engine/types';
import { CircuitBreaker, type BreakerState } from './circuitBreaker';
import type { Rng } from './rng';
import { HOT_DEVICE_ID } from './traffic';

/**
 * A simulated stand-in for everything around the engine: the campaign store and its local config cache, the
 * Kafka -> aggregator -> Redis spend pipeline with per-partition watermarks, the device rate counters, and the Redis
 * circuit breaker. It assembles a ServingState the way ServingStateLoader does, then calls the ported engine.
 *
 * Only the engine is a port. Everything in this file is a model, kept deliberately small.
 */

export const SPEND_PARTITIONS = 6;
/** adgate.config-refresh: every cached configuration is reloaded on this schedule. */
export const CONFIG_REFRESH_MILLIS = 2_000;
/** How far behind real time a healthy spend aggregator runs in the simulation. */
export const PIPELINE_LAG_MILLIS = 600;
/** The partition the "one partition stalled" switch freezes (it carries pixelforge). */
export const STALLED_PARTITION = 2;
/** Background request rate of the hyperactive device, beyond the requests the simulation samples. */
const HOT_DEVICE_REQUESTS_PER_SECOND = 3.2;
const DEVICE_WINDOW_SECONDS = Math.floor(DEFAULT_SETTINGS.deviceRateWindowMillis / 1000);
const MILLIS_PER_DAY = 86_400_000;

export interface Chaos {
  /** Every Redis command fails: serving state, config loads and the spend ledger. */
  readonly redisDown: boolean;
  /** Redis answers in 33-39 ms (inside its 40 ms timeout), plus waiting for a pooled connection. */
  readonly slowRedis: boolean;
  /** The spend aggregator stops: no spend is applied and no watermark moves. */
  readonly spendPipelineStalled: boolean;
  /** Only STALLED_PARTITION stops moving. */
  readonly partitionStalled: boolean;
  /** Campaign configuration cannot be read, so the cache cannot refresh. */
  readonly configRefreshFailing: boolean;
}

export type ChaosSwitch = keyof Chaos;

export const NO_CHAOS: Chaos = {
  redisDown: false,
  slowRedis: false,
  spendPipelineStalled: false,
  partitionStalled: false,
  configRefreshFailing: false,
};

/** What GET /v1/decisions/{id} returns: the request, the exact snapshot the engine saw, and its decisions. */
export interface DecisionRecord {
  readonly request: EligibilityRequest;
  readonly state: ServingState;
  readonly decisions: readonly Decision[];
  /** Simulated time spent reading serving state. */
  readonly loadMillis: number;
}

interface ConfigEntry {
  readonly status: ConfigStatus;
  readonly campaign: Campaign | null;
  readonly loadedAtMillis: number;
}

interface SpendEvent {
  readonly campaignId: string;
  readonly micros: number;
  readonly atMillis: number;
  readonly partition: number;
}

interface RedisRead {
  readonly status: DependencyStatus;
  readonly detail: string;
  readonly deviceRequests: number;
  readonly millis: number;
}

export function utcDay(epochMillis: number): number {
  return Math.floor(epochMillis / MILLIS_PER_DAY);
}

export class World {
  private readonly store = new Map<string, Campaign>();
  private readonly cache = new Map<string, ConfigEntry>();
  private readonly ledger = new Map<string, number>();
  private pending: SpendEvent[] = [];
  private readonly watermarks: number[];
  private readonly deviceCounts = new Map<string, number>();
  private deviceWindow = -1;
  private readonly breaker = new CircuitBreaker();
  private chaosState: Chaos = NO_CHAOS;
  private configHealthySince = Number.NEGATIVE_INFINITY;
  private nowMillis: number;

  constructor(
    startMillis: number,
    campaigns: readonly Campaign[],
    private readonly rng: Rng,
  ) {
    this.nowMillis = startMillis;
    for (const campaign of campaigns) {
      this.store.set(campaign.id, campaign);
      this.cache.set(campaign.id, { status: 'FOUND', campaign, loadedAtMillis: startMillis });
    }
    this.watermarks = Array.from({ length: SPEND_PARTITIONS }, () => startMillis - PIPELINE_LAG_MILLIS);
  }

  get now(): number {
    return this.nowMillis;
  }

  get chaos(): Chaos {
    return this.chaosState;
  }

  setChaos(chaos: Chaos): void {
    const configWasHealthy = this.configReadsHealthy();
    this.chaosState = { ...chaos };
    if (!configWasHealthy && this.configReadsHealthy()) {
      this.configHealthySince = this.nowMillis;
    }
  }

  /** Moves time forward and runs the background work that happens meanwhile. Time is whole milliseconds, as in Java. */
  advanceTo(epochMillis: number): void {
    const now = Math.floor(epochMillis);
    if (now < this.nowMillis) {
      return;
    }
    this.nowMillis = now;
    this.refreshConfigs();
    this.runAggregator();
  }

  /** Admin upsert: writes the store and invalidates the cached entry, as CampaignAdminController does. */
  upsertCampaign(campaign: Campaign): void {
    this.store.set(campaign.id, campaign);
    this.cache.delete(campaign.id);
  }

  campaign(campaignId: string): Campaign | undefined {
    return this.store.get(campaignId);
  }

  /** An auction win, published to the spend topic; the aggregator applies it later. */
  recordSpend(campaignId: string, micros: number): void {
    this.pending.push({ campaignId, micros, atMillis: this.nowMillis, partition: partitionFor(campaignId, SPEND_PARTITIONS) });
  }

  /** Spend the ledger has applied for the current UTC day (what the engine sees). */
  spentToday(campaignId: string): number {
    return this.ledger.get(`${campaignId}:${utcDay(this.nowMillis)}`) ?? 0;
  }

  /** Spend published but not yet applied by the aggregator. */
  pendingSpend(campaignId: string): number {
    return this.pending.filter((event) => event.campaignId === campaignId).reduce((sum, event) => sum + event.micros, 0);
  }

  spendLagMillis(partition: number): number {
    return this.nowMillis - this.watermarks[partition]!;
  }

  /** Age of the cached configuration, or null when it is not cached. */
  configAgeMillis(campaignId: string): number | null {
    const entry = this.cache.get(campaignId);
    return entry ? this.nowMillis - entry.loadedAtMillis : null;
  }

  breakerState(): BreakerState {
    return this.breaker.state(this.nowMillis);
  }

  /**
   * Serves one request the way EligibilityService.evaluate does: assemble the snapshot, enforce the latency
   * budget, run the engine. With countRequest false (replay against current state) nothing is counted.
   */
  decide(request: EligibilityRequest, countRequest = true): DecisionRecord {
    const now = this.nowMillis;
    const campaignIds = [...new Set(request.candidates.map((candidate) => candidate.campaignId))];
    const configs = this.loadConfigs(campaignIds);
    const read = this.readRedis(request.device.id, countRequest);
    const ok = read.status === 'OK';

    const campaigns: Record<string, CampaignState> = {};
    for (const campaignId of campaignIds) {
      const entry = configs.get(campaignId) ?? { status: 'UNAVAILABLE', campaign: null, loadedAtMillis: now };
      const partition = partitionFor(campaignId, SPEND_PARTITIONS);
      campaigns[campaignId] = {
        campaignId,
        configStatus: entry.status,
        campaign: entry.campaign,
        configLoadedAtMillis: entry.loadedAtMillis,
        spentTodayMicros: ok ? this.spentToday(campaignId) : 0,
        spendWatermarkMillis: ok ? this.watermarks[partition]! : null,
        spendPartition: partition,
      };
    }
    let state: ServingState = {
      nowMillis: now,
      dependencyStatus: read.status,
      dependencyDetail: read.detail,
      deviceRequestsInWindow: read.deviceRequests,
      campaigns,
    };
    const loadMillis = read.millis + (this.chaosState.slowRedis && ok ? this.rng.between(8, 22) : this.rng.between(0.05, 0.3));
    if (countRequest) {
      state = applyLatencyBudget(state, Math.round(loadMillis * 1_000_000), LATENCY_BUDGET_MILLIS);
    }
    return { request, state, decisions: evaluate(request, state, DEFAULT_SETTINGS), loadMillis };
  }

  private configReadsHealthy(): boolean {
    return !this.chaosState.redisDown && !this.chaosState.configRefreshFailing;
  }

  /** CampaignConfigCache.getAll: cached entries as they are; misses are loaded, or UNAVAILABLE if loading fails. */
  private loadConfigs(campaignIds: readonly string[]): Map<string, ConfigEntry> {
    const entries = new Map<string, ConfigEntry>();
    for (const campaignId of campaignIds) {
      let entry = this.cache.get(campaignId);
      if (entry === undefined && this.configReadsHealthy()) {
        entry = this.loadFromStore(campaignId, this.nowMillis);
        this.cache.set(campaignId, entry);
      }
      if (entry !== undefined) {
        entries.set(campaignId, entry);
      }
    }
    return entries;
  }

  private loadFromStore(campaignId: string, at: number): ConfigEntry {
    const campaign = this.store.get(campaignId);
    return campaign ? { status: 'FOUND', campaign, loadedAtMillis: at } : { status: 'NOT_FOUND', campaign: null, loadedAtMillis: at };
  }

  /** The scheduled refresh: on every boundary, every cached entry is reloaded, unless reads fail. */
  private refreshConfigs(): void {
    if (!this.configReadsHealthy()) {
      return;
    }
    const boundary = Math.floor(this.nowMillis / CONFIG_REFRESH_MILLIS) * CONFIG_REFRESH_MILLIS;
    if (boundary < this.configHealthySince) {
      return;
    }
    for (const [campaignId, entry] of this.cache) {
      if (boundary > entry.loadedAtMillis) {
        this.cache.set(campaignId, this.loadFromStore(campaignId, boundary));
      }
    }
  }

  /** The spend aggregator: applies published spend to the ledger and moves each partition's watermark. */
  private runAggregator(): void {
    if (this.chaosState.redisDown || this.chaosState.spendPipelineStalled) {
      return;
    }
    const caughtUpTo = this.nowMillis - PIPELINE_LAG_MILLIS;
    const stalled = this.chaosState.partitionStalled ? STALLED_PARTITION : -1;
    if (this.pending.length > 0) {
      const remaining: SpendEvent[] = [];
      for (const event of this.pending) {
        if (event.partition !== stalled && event.atMillis <= caughtUpTo) {
          const key = `${event.campaignId}:${utcDay(event.atMillis)}`;
          this.ledger.set(key, (this.ledger.get(key) ?? 0) + event.micros);
        } else {
          remaining.push(event);
        }
      }
      this.pending = remaining;
    }
    for (let partition = 0; partition < SPEND_PARTITIONS; partition++) {
      if (partition !== stalled) {
        this.watermarks[partition] = Math.max(this.watermarks[partition]!, caughtUpTo);
      }
    }
  }

  /** RedisStateReader.read: one pipelined round trip behind the circuit breaker. */
  private readRedis(deviceId: string, countRequest: boolean): RedisRead {
    const now = this.nowMillis;
    if (!this.breaker.tryAcquire(now)) {
      return { status: 'CIRCUIT_OPEN', detail: "circuit breaker 'redis' is open", deviceRequests: 0, millis: this.rng.between(0.01, 0.04) };
    }
    if (this.chaosState.redisDown) {
      const millis = this.rng.between(0.2, 0.6);
      this.breaker.record(now, true, millis);
      return { status: 'UNAVAILABLE', detail: 'RedisConnectionException: Unable to connect to 127.0.0.1:6379', deviceRequests: 0, millis };
    }
    const millis = this.chaosState.slowRedis ? this.rng.between(33, 39) : this.rng.between(0.35, 1.4);
    this.breaker.record(now, false, millis);
    return { status: 'OK', detail: '', deviceRequests: this.deviceCounter(deviceId, countRequest), millis };
  }

  /** INCR (or GET) of the device's counter for the current fixed window. */
  private deviceCounter(deviceId: string, increment: boolean): number {
    const nowSeconds = Math.floor(this.nowMillis / 1000);
    const windowStart = Math.floor(nowSeconds / DEVICE_WINDOW_SECONDS) * DEVICE_WINDOW_SECONDS;
    if (windowStart !== this.deviceWindow) {
      this.deviceCounts.clear();
      this.deviceWindow = windowStart;
    }
    const counted = (this.deviceCounts.get(deviceId) ?? 0) + (increment ? 1 : 0);
    this.deviceCounts.set(deviceId, counted);
    const background =
      deviceId === HOT_DEVICE_ID ? Math.floor((this.nowMillis / 1000 - windowStart) * HOT_DEVICE_REQUESTS_PER_SECOND) : 0;
    return counted + background;
  }
}
