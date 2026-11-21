/**
 * Types of the eligibility engine, mirroring the Java records and enums in
 * eligibility-service/src/main/java/io/github/gargshishirr/adgate/eligibility/{domain,engine}.
 * Money is in integer micros and time in epoch milliseconds, as in the Java code.
 */

export const PLACEMENTS = ['SEARCH_RESULTS', 'SEARCH_TAB', 'TODAY_TAB', 'PRODUCT_PAGES'] as const;
export type Placement = (typeof PLACEMENTS)[number];

export type CampaignStatus = 'ACTIVE' | 'PAUSED';

/** EVEN spreads the daily budget across the UTC day; ASAP spends as fast as traffic allows. */
export type PacingMode = 'EVEN' | 'ASAP';

export type ConfigStatus = 'FOUND' | 'NOT_FOUND' | 'UNAVAILABLE';

/** Whether the shared serving state could be read for this request. */
export type DependencyStatus = 'OK' | 'UNAVAILABLE' | 'CIRCUIT_OPEN' | 'DEADLINE_EXCEEDED';

export type ReasonCode =
  | 'ELIGIBLE'
  | 'DEPENDENCY_UNAVAILABLE'
  | 'DEADLINE_EXCEEDED'
  | 'TRAFFIC_INVALID_USER_AGENT'
  | 'TRAFFIC_RATE_EXCEEDED'
  | 'CAMPAIGN_NOT_FOUND'
  | 'CONFIG_STALE'
  | 'CAMPAIGN_PAUSED'
  | 'POLICY_PLACEMENT_NOT_ALLOWED'
  | 'POLICY_NEGATIVE_KEYWORD'
  | 'SPEND_DATA_STALE'
  | 'BUDGET_EXHAUSTED'
  | 'PACING_THROTTLED';

export interface Campaign {
  readonly id: string;
  readonly status: CampaignStatus;
  readonly dailyBudgetMicros: number;
  readonly pacing: PacingMode;
  /** Placements the campaign may serve on; empty means all. */
  readonly placements: readonly Placement[];
  /** Phrases that must not appear in the search query. */
  readonly negativeKeywords: readonly string[];
}

export interface Candidate {
  readonly adId: string;
  readonly campaignId: string;
}

export interface Device {
  readonly id: string;
  readonly userAgent: string | null;
}

export interface EligibilityRequest {
  readonly requestId: string;
  readonly placement: Placement;
  readonly query: string | null;
  readonly device: Device;
  readonly candidates: readonly Candidate[];
}

/** Everything the engine knows about one campaign at decision time. */
export interface CampaignState {
  readonly campaignId: string;
  readonly configStatus: ConfigStatus;
  /** Present only when configStatus is FOUND. */
  readonly campaign: Campaign | null;
  readonly configLoadedAtMillis: number;
  readonly spentTodayMicros: number;
  /** Low watermark of the partition that carries this campaign's spend, or null if unknown. */
  readonly spendWatermarkMillis: number | null;
  readonly spendPartition: number;
}

/** A complete snapshot of the inputs to one decision. The engine never reads the clock: time is nowMillis. */
export interface ServingState {
  readonly nowMillis: number;
  readonly dependencyStatus: DependencyStatus;
  readonly dependencyDetail: string | null;
  /** Requests from this device in the current traffic-quality window, this one included. */
  readonly deviceRequestsInWindow: number;
  readonly campaigns: Readonly<Record<string, CampaignState>>;
}

export interface Decision {
  readonly adId: string;
  readonly campaignId: string;
  readonly eligible: boolean;
  readonly reason: ReasonCode;
  /** Human-readable detail. Null only if the service passed a DEADLINE_EXCEEDED state without a detail. */
  readonly detail: string | null;
}

/** Tunables of the engine; see EngineSettings.java. Durations are in milliseconds. */
export interface EngineSettings {
  readonly maxSpendStalenessMillis: number;
  readonly maxConfigAgeMillis: number;
  readonly deviceRequestLimit: number;
  readonly deviceRateWindowMillis: number;
  readonly pacingBurst: number;
  readonly pacingEarlyDayAllowance: number;
  readonly pacingRampFraction: number;
  readonly budgetSafetyMargin: number;
  readonly blockedUserAgentTokens: readonly string[];
}
