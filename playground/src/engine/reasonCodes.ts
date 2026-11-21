import type { ReasonCode } from './types';

export interface ReasonCodeInfo {
  readonly code: ReasonCode;
  /** "We could not prove it is safe to serve", as opposed to "we proved it is not". */
  readonly failClosed: boolean;
  /** ReasonCode.description() in the Java enum. */
  readonly description: string;
}

/** The ReasonCode enum, in declaration order (which is also the order of the checks). */
export const REASON_CODES: readonly ReasonCodeInfo[] = [
  { code: 'ELIGIBLE', failClosed: false, description: 'Candidate passed every check and may serve' },
  { code: 'DEPENDENCY_UNAVAILABLE', failClosed: true, description: 'Serving state could not be read (Redis error, timeout or open circuit)' },
  { code: 'DEADLINE_EXCEEDED', failClosed: true, description: 'The latency budget ran out before serving state was read' },
  { code: 'TRAFFIC_INVALID_USER_AGENT', failClosed: false, description: "The request's user agent matches a known automation pattern" },
  { code: 'TRAFFIC_RATE_EXCEEDED', failClosed: false, description: 'The device exceeded the request rate allowed in the traffic-quality window' },
  { code: 'CAMPAIGN_NOT_FOUND', failClosed: false, description: 'No configuration exists for the campaign' },
  { code: 'CONFIG_STALE', failClosed: true, description: 'The campaign configuration could not be refreshed within its allowed age' },
  { code: 'CAMPAIGN_PAUSED', failClosed: false, description: 'The campaign is paused' },
  { code: 'POLICY_PLACEMENT_NOT_ALLOWED', failClosed: false, description: 'The campaign may not serve on this placement' },
  { code: 'POLICY_NEGATIVE_KEYWORD', failClosed: false, description: "The search query matches one of the campaign's negative keywords" },
  { code: 'SPEND_DATA_STALE', failClosed: true, description: "Spend for the campaign's partition is older than the allowed staleness" },
  { code: 'BUDGET_EXHAUSTED', failClosed: false, description: 'The campaign has spent its daily budget' },
  { code: 'PACING_THROTTLED', failClosed: false, description: 'The campaign is ahead of its even-pacing schedule' },
];

const BY_CODE = new Map(REASON_CODES.map((info) => [info.code, info]));

export function reasonInfo(code: ReasonCode): ReasonCodeInfo {
  const info = BY_CODE.get(code);
  if (!info) {
    throw new Error(`Unknown reason code ${code}`);
  }
  return info;
}

export function isFailClosed(code: ReasonCode): boolean {
  return reasonInfo(code).failClosed;
}
