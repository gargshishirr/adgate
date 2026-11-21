import type { EngineSettings } from './types';

/** EngineSettings.defaults() and the adgate.engine block of application.yml. */
export const DEFAULT_SETTINGS: EngineSettings = Object.freeze({
  maxSpendStalenessMillis: 10_000,
  maxConfigAgeMillis: 30_000,
  deviceRequestLimit: 20,
  deviceRateWindowMillis: 10_000,
  pacingBurst: 0.1,
  pacingEarlyDayAllowance: 0.02,
  pacingRampFraction: 0.02,
  budgetSafetyMargin: 0.005,
  blockedUserAgentTokens: Object.freeze(['bot', 'crawler', 'spider', 'headless', 'python-requests']),
});

/** adgate.latency-budget in application.yml: time allowed to assemble serving state. */
export const LATENCY_BUDGET_MILLIS = 50;
