import { describe, expect, it } from 'vitest';

import { evaluate } from '../engine/engine';
import type { Campaign, EligibilityRequest } from '../engine/types';
import { CircuitBreaker } from './circuitBreaker';
import { winCostMicros } from './market';
import { Rng } from './rng';
import { generateRequest, HOT_DEVICE_ID } from './traffic';
import { NO_CHAOS, STALLED_PARTITION, World, type Chaos, type DecisionRecord } from './world';

const START = Date.UTC(2026, 10, 19, 12);

const lumen: Campaign = {
  id: 'lumen-fitness', // spend partition 0
  status: 'ACTIVE',
  dailyBudgetMicros: 400_000_000,
  pacing: 'ASAP',
  placements: [],
  negativeKeywords: [],
};
const pixelforge: Campaign = { ...lumen, id: 'pixelforge' }; // spend partition 2

function request(...campaignIds: string[]): EligibilityRequest {
  return {
    requestId: 'r-1',
    placement: 'SEARCH_RESULTS',
    query: 'photo editor',
    device: { id: 'device-1', userAgent: 'AppStore/3.0 iOS/19.1' },
    candidates: campaignIds.map((campaignId) => ({ adId: `ad-${campaignId}`, campaignId })),
  };
}

function newWorld(): World {
  return new World(START, [lumen, pixelforge], new Rng(7));
}

function reasons(record: DecisionRecord): string[] {
  return record.decisions.map((decision) => decision.reason);
}

function withChaos(world: World, patch: Partial<Chaos>): void {
  world.setChaos({ ...world.chaos, ...patch });
}

describe('simulated world', () => {
  it('serves normally when everything is healthy', () => {
    const world = newWorld();
    world.advanceTo(START + 5_000);
    expect(reasons(world.decide(request('lumen-fitness', 'pixelforge')))).toEqual(['ELIGIBLE', 'ELIGIBLE']);
  });

  it('fails closed on stale spend while the pipeline is stalled, then applies the backlog', () => {
    const world = newWorld();
    world.advanceTo(START + 1_000);
    world.recordSpend('lumen-fitness', 5_000_000);
    world.advanceTo(START + 2_000);
    expect(world.spentToday('lumen-fitness')).toBe(5_000_000);

    withChaos(world, { spendPipelineStalled: true });
    world.recordSpend('lumen-fitness', 7_000_000);
    world.advanceTo(START + 11_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['ELIGIBLE']);
    world.advanceTo(START + 13_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['SPEND_DATA_STALE']);
    expect(world.spentToday('lumen-fitness')).toBe(5_000_000);

    withChaos(world, { spendPipelineStalled: false });
    world.advanceTo(START + 14_000);
    expect(world.spentToday('lumen-fitness')).toBe(12_000_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['ELIGIBLE']);
  });

  it('a stalled partition fails closed only the campaigns it carries', () => {
    expect(STALLED_PARTITION).toBe(2);
    const world = newWorld();
    withChaos(world, { partitionStalled: true });
    world.advanceTo(START + 60_000);
    expect(reasons(world.decide(request('lumen-fitness', 'pixelforge')))).toEqual(['ELIGIBLE', 'SPEND_DATA_STALE']);
  });

  it('fails closed on stale configuration, and recovers at the next refresh', () => {
    const world = newWorld();
    world.advanceTo(START + 1_000);
    withChaos(world, { configRefreshFailing: true });
    world.advanceTo(START + 30_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['ELIGIBLE']);
    world.advanceTo(START + 30_001);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['CONFIG_STALE']);

    withChaos(world, { configRefreshFailing: false });
    world.advanceTo(START + 31_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['CONFIG_STALE']);
    world.advanceTo(START + 32_000);
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['ELIGIBLE']);
  });

  it('declines when Redis is down, opens the breaker, and closes it again after recovery', () => {
    const world = newWorld();
    withChaos(world, { redisDown: true });
    const first = world.decide(request('lumen-fitness'));
    expect(first.state.dependencyStatus).toBe('UNAVAILABLE');
    expect(reasons(first)).toEqual(['DEPENDENCY_UNAVAILABLE']);
    for (let i = 0; i < 25; i++) {
      world.decide(request('lumen-fitness'));
    }
    expect(world.breakerState()).toBe('OPEN');
    const rejected = world.decide(request('lumen-fitness'));
    expect(rejected.state.dependencyStatus).toBe('CIRCUIT_OPEN');
    expect(rejected.decisions[0]!.detail).toBe("CIRCUIT_OPEN: circuit breaker 'redis' is open");

    withChaos(world, { redisDown: false });
    world.advanceTo(START + 2_500);
    for (let i = 0; i < 5; i++) {
      expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['ELIGIBLE']);
    }
    expect(world.breakerState()).toBe('CLOSED');
  });

  it('slow Redis exceeds the latency budget', () => {
    const world = newWorld();
    withChaos(world, { slowRedis: true });
    const records = Array.from({ length: 15 }, () => world.decide(request('lumen-fitness')));
    const late = records.filter((record) => record.loadMillis > 50);
    expect(late.length).toBeGreaterThan(0);
    for (const record of records) {
      expect(reasons(record)).toEqual([record.loadMillis > 50 ? 'DEADLINE_EXCEEDED' : 'ELIGIBLE']);
    }
  });

  it('applies an edit at once, unless configuration cannot be read', () => {
    const world = newWorld();
    world.upsertCampaign({ ...lumen, status: 'PAUSED' });
    expect(reasons(world.decide(request('lumen-fitness')))).toEqual(['CAMPAIGN_PAUSED']);

    withChaos(world, { configRefreshFailing: true });
    world.upsertCampaign({ ...lumen, status: 'ACTIVE' });
    const record = world.decide(request('lumen-fitness'));
    expect(record.state.campaigns['lumen-fitness']!.configStatus).toBe('UNAVAILABLE');
    expect(reasons(record)).toEqual(['DEPENDENCY_UNAVAILABLE']);
  });

  it('counts device requests per fixed window, and replays do not count', () => {
    const world = newWorld();
    for (let i = 1; i <= 21; i++) {
      const record = world.decide(request('lumen-fitness'));
      expect(record.state.deviceRequestsInWindow).toBe(i);
      expect(reasons(record)).toEqual([i > 20 ? 'TRAFFIC_RATE_EXCEEDED' : 'ELIGIBLE']);
    }
    expect(world.decide(request('lumen-fitness'), false).state.deviceRequestsInWindow).toBe(21);
    world.advanceTo(START + 10_000);
    expect(world.decide(request('lumen-fitness')).state.deviceRequestsInWindow).toBe(1);
  });

  it('unknown campaigns are NOT_FOUND, or UNAVAILABLE when configuration cannot be loaded', () => {
    const world = newWorld();
    expect(reasons(world.decide(request('sunset-sale')))).toEqual(['CAMPAIGN_NOT_FOUND']);
    const cold = newWorld();
    withChaos(cold, { configRefreshFailing: true });
    expect(reasons(cold.decide(request('sunset-sale')))).toEqual(['DEPENDENCY_UNAVAILABLE']);
  });

  it('snapshots replay to the same decisions', () => {
    const world = newWorld();
    world.advanceTo(START + 3_000);
    const record = world.decide(request('lumen-fitness', 'pixelforge', 'sunset-sale'));
    expect(evaluate(record.request, record.state)).toEqual(record.decisions);
  });

  it('starts healthy', () => {
    expect(newWorld().chaos).toEqual(NO_CHAOS);
  });
});

describe('circuit breaker', () => {
  it('opens on failures, half-opens after the wait, and closes after successful trial calls', () => {
    const breaker = new CircuitBreaker();
    for (let i = 0; i < 19; i++) {
      breaker.record(0, true, 1);
    }
    expect(breaker.state(0)).toBe('CLOSED');
    breaker.record(0, true, 1);
    expect(breaker.state(0)).toBe('OPEN');
    expect(breaker.tryAcquire(1_999)).toBe(false);
    expect(breaker.tryAcquire(2_000)).toBe(true);
    expect(breaker.state(2_000)).toBe('HALF_OPEN');
    for (let i = 0; i < 4; i++) {
      breaker.tryAcquire(2_000);
    }
    expect(breaker.tryAcquire(2_000)).toBe(false);
    for (let i = 0; i < 5; i++) {
      breaker.record(2_000, false, 1);
    }
    expect(breaker.state(2_000)).toBe('CLOSED');
  });

  it('opens when most calls are slow', () => {
    const breaker = new CircuitBreaker();
    for (let i = 0; i < 20; i++) {
      breaker.record(0, false, 35);
    }
    expect(breaker.state(0)).toBe('OPEN');
  });
});

describe('traffic and auctions', () => {
  it('generates the same traffic from the same seed, with the configured mix', () => {
    const ids = ['a', 'b', 'c', 'd'];
    const first = Array.from({ length: 50 }, ((rng) => () => generateRequest(rng, ids, 'gone'))(new Rng(3)));
    const second = Array.from({ length: 50 }, ((rng) => () => generateRequest(rng, ids, 'gone'))(new Rng(3)));
    expect(first).toEqual(second);

    const rng = new Rng(11);
    const sample = Array.from({ length: 20_000 }, () => generateRequest(rng, ids, 'gone'));
    const share = (predicate: (r: EligibilityRequest) => boolean) => sample.filter(predicate).length / sample.length;
    expect(share((r) => r.device.id === HOT_DEVICE_ID)).toBeCloseTo(0.02, 2);
    expect(share((r) => r.candidates.some((c) => c.campaignId === 'gone'))).toBeCloseTo(0.02, 2);
    expect(share((r) => r.placement === 'SEARCH_RESULTS')).toBeCloseTo(0.5, 1);
  });

  it('prices a win by the slice of traffic the request stands for', () => {
    const bidder = { dailyBudgetMicros: 100_000_000, demandHours: 10 };
    const average = (slice: number) => {
      const rng = new Rng(5);
      return Array.from({ length: 2_000 }, () => winCostMicros(bidder, slice, rng)).reduce((a, b) => a + b, 0) / 2_000;
    };
    expect(average(20_000) / average(10_000)).toBeCloseTo(2, 1);
  });
});
