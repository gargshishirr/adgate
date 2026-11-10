package io.github.gargshishirr.adgate.eligibility.engine;

/** Even-pacing math. Daily budgets reset at midnight UTC. */
public final class PacingPolicy {

    static final long MILLIS_PER_DAY = 86_400_000L;

    private PacingPolicy() {
    }

    /** Fraction of the UTC day that has elapsed, in [0, 1). */
    public static double dayFraction(long epochMillis) {
        return Math.floorMod(epochMillis, MILLIS_PER_DAY) / (double) MILLIS_PER_DAY;
    }

    /**
     * Spend the even-pacing schedule allows by now: the straight line from 0 at midnight to the full budget at
     * the end of the day, stretched by {@code burst} and lifted by {@code earlyDayAllowance}, capped at the budget.
     */
    public static long evenPacingAllowanceMicros(long dailyBudgetMicros, double dayFraction, double burst, double earlyDayAllowance) {
        double share = Math.min(1.0, dayFraction * (1.0 + burst) + earlyDayAllowance);
        return (long) Math.floor(dailyBudgetMicros * share);
    }
}
