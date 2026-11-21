/** Port of PacingPolicy.java: even-pacing math. Daily budgets reset at midnight UTC. */

export const MILLIS_PER_DAY = 86_400_000;

/** Fraction of the UTC day that has elapsed, in [0, 1). Math.floorMod semantics for negative times. */
export function dayFraction(epochMillis: number): number {
  return (((epochMillis % MILLIS_PER_DAY) + MILLIS_PER_DAY) % MILLIS_PER_DAY) / MILLIS_PER_DAY;
}

/**
 * Spend the even-pacing schedule allows by now: the straight line from 0 at midnight to the full budget at the
 * end of the day, stretched by burst and lifted by earlyDayAllowance, capped at the budget.
 */
export function evenPacingAllowanceMicros(
  dailyBudgetMicros: number,
  fractionOfDay: number,
  burst: number,
  earlyDayAllowance: number,
): number {
  const share = Math.min(1.0, fractionOfDay * (1.0 + burst) + earlyDayAllowance);
  return Math.floor(dailyBudgetMicros * share);
}
