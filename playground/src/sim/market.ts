import type { Rng } from './rng';

/**
 * The simulated auction that turns eligible candidates into spend. Every simulated request stands in for a slice of
 * a busy ad server's traffic (the simulated time since the previous request), so a campaign's spend per simulated
 * hour stays the same whatever the clock speed or request rate.
 */

/** Chance that a request with at least one eligible candidate produces a win (traffic-sim's win-rate). */
export const WIN_RATE = 0.35;

/** Corrects for a campaign being one of 3 candidates out of 4, and sharing wins with about 2 other eligible ones. */
const WIN_SCALE = 1 / ((0.75 * WIN_RATE) / 2);

export interface Bidder {
  readonly dailyBudgetMicros: number;
  /** Hours the campaign would take to spend its whole budget if nothing held it back. */
  readonly demandHours: number;
}

export function winCostMicros(bidder: Bidder, sliceMillis: number, rng: Rng): number {
  const expected = ((bidder.dailyBudgetMicros * sliceMillis) / (bidder.demandHours * 3_600_000)) * WIN_SCALE;
  return Math.max(1, Math.round(expected * rng.between(0.6, 1.4)));
}

/** The winner among the eligible campaigns, or null when the request did not win an auction. */
export function runAuction(eligibleCampaignIds: readonly string[], rng: Rng): string | null {
  if (eligibleCampaignIds.length === 0 || !rng.chance(WIN_RATE)) {
    return null;
  }
  return rng.pick(eligibleCampaignIds);
}
