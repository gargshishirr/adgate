import { formatFixed } from './javaFormat';
import type { ServingState } from './types';

/**
 * Port of the latency-budget step in EligibilityService.evaluate (it wraps the pure engine): if reading serving
 * state took longer than the budget, every candidate is declined with DEADLINE_EXCEEDED, because the ad server could
 * not have used a later answer anyway.
 */
export function applyLatencyBudget(state: ServingState, loadNanos: number, latencyBudgetMillis: number): ServingState {
  if (state.dependencyStatus === 'OK' && loadNanos > latencyBudgetMillis * 1_000_000) {
    return {
      ...state,
      dependencyStatus: 'DEADLINE_EXCEEDED',
      dependencyDetail: `reading serving state took ${formatFixed(loadNanos / 1e6, 1)} ms (budget ${latencyBudgetMillis} ms)`,
    };
  }
  return state;
}
