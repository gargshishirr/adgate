import { describe, expect, it } from 'vitest';

import { Simulation } from './simulation';

const DAY = Date.UTC(2026, 10, 19);
const HOUR = 3_600_000;

function spentPercent(sim: Simulation, campaignId: string): number {
  const campaign = sim.world.campaign(campaignId)!;
  return (100 * sim.world.spentToday(campaignId)) / campaign.dailyBudgetMicros;
}

describe('simulation', () => {
  it('is deterministic for a seed', () => {
    const run = () => {
      const sim = new Simulation({ seed: 9, dayStartMillis: DAY, arriveAtMillisOfDay: 2 * HOUR });
      sim.skip(HOUR);
      return sim.log.slice(0, 50).map((entry) => [entry.request.requestId, entry.decisions.map((d) => d.reason)]);
    };
    expect(run()).toEqual(run());
  });

  it('even pacing holds spend near the pacing line through the day; ASAP runs out early', () => {
    const sim = new Simulation({ seed: 1, dayStartMillis: DAY, arriveAtMillisOfDay: 0 });
    for (let hour = 1; hour <= 23; hour++) {
      sim.skip(HOUR);
      const line = sim.pacingLinePercent();
      for (const id of ['lumen-fitness', 'pixelforge']) {
        const spent = spentPercent(sim, id);
        expect(spent, `${id} at ${hour}:00`).toBeLessThanOrEqual(line + 3);
        if (hour >= 3) {
          expect(spent, `${id} at ${hour}:00`).toBeGreaterThan(line - 6);
        }
      }
      if (hour === 16) {
        expect(spentPercent(sim, 'trailmix-travel')).toBeGreaterThanOrEqual(99.5);
      }
    }
    expect(sim.recentReasons().find((row) => row.code === 'BUDGET_EXHAUSTED')!.count).toBeGreaterThan(0);
  });

  it('starts a new budget day at midnight UTC', () => {
    const sim = new Simulation({ seed: 2, dayStartMillis: DAY, arriveAtMillisOfDay: 23 * HOUR });
    expect(spentPercent(sim, 'lumen-fitness')).toBeGreaterThan(50);
    sim.skip(2 * HOUR);
    expect(new Date(sim.now).getUTCHours()).toBe(1);
    expect(spentPercent(sim, 'lumen-fitness')).toBeLessThan(10);
    expect(sim.spendHistory('lumen-fitness')[0]!.atMillis).toBe(DAY + 24 * HOUR);
  });

  it('the clock follows the selected speed and stops when paused', () => {
    const sim = new Simulation({ seed: 3, dayStartMillis: DAY, arriveAtMillisOfDay: HOUR });
    const start = sim.now;
    sim.setSpeed(600);
    sim.tick(100);
    expect(sim.now - start).toBe(60_000);
    sim.setClockRunning(false);
    sim.tick(100);
    expect(sim.now - start).toBe(60_000);
  });

  it('live traffic produces decisions at the chosen rate', () => {
    const sim = new Simulation({ seed: 4, dayStartMillis: DAY, arriveAtMillisOfDay: HOUR });
    const before = sim.requestsToday;
    sim.setRate(20);
    sim.setTraffic(true);
    for (let i = 0; i < 10; i++) {
      sim.tick(100);
    }
    expect(sim.requestsToday - before).toBeGreaterThanOrEqual(19);
    expect(sim.requestsToday - before).toBeLessThanOrEqual(21);
    expect(sim.milestones.has('traffic')).toBe(true);
  });

  it('fast clocks send more requests, so live spend still tracks the pacing line', () => {
    const sim = new Simulation({ seed: 7, dayStartMillis: DAY, arriveAtMillisOfDay: 8 * HOUR });
    sim.setSpeed(3600);
    sim.setTraffic(true);
    expect(sim.effectiveRate()).toBe(120);
    sim.setSpeed(60);
    expect(sim.effectiveRate()).toBe(sim.rate);
    sim.setSpeed(3600);
    for (let i = 0; i < 600; i++) {
      sim.tick(16);
      const line = sim.pacingLinePercent();
      for (const id of ['lumen-fitness', 'pixelforge']) {
        expect(spentPercent(sim, id), `${id} at ${new Date(sim.now).toISOString()}`).toBeLessThanOrEqual(line + 3);
      }
    }
    expect(new Date(sim.now).getUTCHours()).toBeGreaterThanOrEqual(17);
  });

  it('a hyperactive device is cut off by the rate limit', () => {
    const sim = new Simulation({ seed: 5, dayStartMillis: DAY, arriveAtMillisOfDay: HOUR });
    const last = sim.send({ placement: 'SEARCH_RESULTS', query: 'home workout', deviceId: 'burst', campaignIds: ['lumen-fitness'] });
    expect(last.state.deviceRequestsInWindow).toBe(24);
    expect(last.decisions[0]!.reason).toBe('TRAFFIC_RATE_EXCEEDED');
    expect(sim.log.filter((entry) => entry.source === 'you')).toHaveLength(24);
  });

  it('chaos switches fail closed and recover', () => {
    const sim = new Simulation({ seed: 6, dayStartMillis: DAY, arriveAtMillisOfDay: 10 * HOUR });
    const send = () =>
      sim.send({ placement: 'SEARCH_RESULTS', query: 'home workout', deviceId: 'iphone', campaignIds: ['lumen-fitness', 'kitchenly-recipes'] });

    sim.setChaos('spendPipelineStalled', true);
    sim.skip(60_000);
    expect(send().decisions.map((d) => d.reason)).toEqual(['SPEND_DATA_STALE', 'SPEND_DATA_STALE']);
    sim.setChaos('spendPipelineStalled', false);
    sim.skip(1_000);
    expect(send().decisions.every((d) => d.reason !== 'SPEND_DATA_STALE')).toBe(true);

    sim.setChaos('redisDown', true);
    expect(send().decisions.map((d) => d.reason)).toEqual(['DEPENDENCY_UNAVAILABLE', 'DEPENDENCY_UNAVAILABLE']);
    sim.recoverAll();
    sim.skip(10_000);
    expect(send().decisions.every((d) => d.reason !== 'DEPENDENCY_UNAVAILABLE')).toBe(true);
    expect(sim.milestones.has('chaos')).toBe(true);
  });
});
