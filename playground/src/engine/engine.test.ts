import { describe, expect, it } from 'vitest';

import { CHECKS, evaluate, evaluateTraced } from './engine';
import { formatFixed, hourMinuteUtc, isJavaBlank } from './javaFormat';
import { applyLatencyBudget } from './latencyBudget';
import { unit } from './sampler';
import type { CampaignState, EligibilityRequest, ServingState } from './types';

const NOON = Date.UTC(2026, 10, 19, 12);

const request: EligibilityRequest = {
  requestId: 'r-1',
  placement: 'SEARCH_RESULTS',
  query: 'photo editor',
  device: { id: 'd-1', userAgent: 'AppStore/3.0 iOS/19.1' },
  candidates: [{ adId: 'ad-1', campaignId: 'c-1' }],
};

function state(spent: number, overrides: Partial<ServingState> = {}): ServingState {
  const campaign: CampaignState = {
    campaignId: 'c-1',
    configStatus: 'FOUND',
    campaign: { id: 'c-1', status: 'ACTIVE', dailyBudgetMicros: 100_000_000, pacing: 'EVEN', placements: [], negativeKeywords: ['free'] },
    configLoadedAtMillis: NOON - 1_000,
    spentTodayMicros: spent,
    spendWatermarkMillis: NOON - 500,
    spendPartition: 4,
  };
  return { nowMillis: NOON, dependencyStatus: 'OK', dependencyDetail: '', deviceRequestsInWindow: 1, campaigns: { 'c-1': campaign }, ...overrides };
}

describe('Java-compatible formatting', () => {
  it('rounds the shortest decimal half up, like String.format, not like toFixed', () => {
    expect(formatFixed(1.005, 2)).toBe('1.01');
    expect((1.005).toFixed(2)).toBe('1.00');
    expect(formatFixed(0.35, 1)).toBe('0.4');
    expect(formatFixed(2.675, 2)).toBe('2.68');
    expect(formatFixed(12.25, 1)).toBe('12.3');
    expect(formatFixed(0, 1)).toBe('0.0');
    expect(formatFixed(99.95, 0)).toBe('100');
    expect(formatFixed(-0.04, 1)).toBe('-0.0');
    expect(formatFixed(1e21, 2)).toBe('1000000000000000000000.00');
  });

  it('formats the UTC hour and minute and treats only Java whitespace as blank', () => {
    expect(hourMinuteUtc(Date.UTC(2026, 0, 1, 9, 5, 59))).toBe('09:05Z');
    expect(isJavaBlank(' \t\n')).toBe(true);
    expect(isJavaBlank('\u00a0')).toBe(false);
  });
});

describe('engine', () => {
  it('records the checks in the engine order and stops at the first failure', () => {
    const traced = evaluateTraced({ ...request, query: 'FREE photo editor' }, state(0));
    const ids = traced.traces[0]!.checks.map((check) => check.id);
    expect(ids).toEqual(CHECKS.slice(0, ids.length).map((check) => check.id));
    expect(traced.traces[0]!.checks.at(-1)).toMatchObject({ id: 'negativeKeywords', outcome: 'fail' });
    expect(traced.decisions[0]!.reason).toBe('POLICY_NEGATIVE_KEYWORD');
  });

  it('runs every check for an eligible even-paced candidate', () => {
    const traced = evaluateTraced(request, state(10_000_000));
    expect(traced.traces[0]!.checks.map((check) => check.id)).toEqual(CHECKS.map((check) => check.id));
    expect(traced.decisions[0]).toMatchObject({ eligible: true, reason: 'ELIGIBLE', detail: 'spent 10.0% of daily budget' });
  });

  it('thins traffic near the pacing line with the deterministic draw', () => {
    // 1M of headroom in a 2M ramp at noon: serve when the draw is below 0.5.
    const nearLine = state(56_000_000);
    for (const requestId of ['r-1', 'r-2', 'r-3', 'r-4', 'r-5', 'r-6']) {
      const decision = evaluate({ ...request, requestId }, nearLine)[0]!;
      expect(decision.eligible).toBe(unit(requestId, 'c-1') < 0.5);
    }
  });
});

describe('latency budget (EligibilityService)', () => {
  it('turns a slow but successful read into DEADLINE_EXCEEDED for every candidate', () => {
    const late = applyLatencyBudget(state(0), 63_250_000, 50);
    expect(late.dependencyStatus).toBe('DEADLINE_EXCEEDED');
    expect(late.dependencyDetail).toBe('reading serving state took 63.3 ms (budget 50 ms)');
    expect(evaluate(request, late)[0]!.reason).toBe('DEADLINE_EXCEEDED');
  });

  it('leaves fast reads and already failed reads alone', () => {
    expect(applyLatencyBudget(state(0), 49_000_000, 50).dependencyStatus).toBe('OK');
    const failed = state(0, { dependencyStatus: 'CIRCUIT_OPEN', dependencyDetail: "circuit breaker 'redis' is open" });
    expect(applyLatencyBudget(failed, 80_000_000, 50)).toBe(failed);
  });
});
