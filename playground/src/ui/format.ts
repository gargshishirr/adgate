import type { Placement, ReasonCode } from '../engine/types';

export function money(micros: number): string {
  const units = micros / 1_000_000;
  return `$${units.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

export function wholeMoney(micros: number): string {
  return `$${Math.round(micros / 1_000_000).toLocaleString('en-US')}`;
}

export function percent(value: number, digits = 1): string {
  return `${value.toFixed(digits)}%`;
}

function pad(value: number, width = 2): string {
  return String(value).padStart(width, '0');
}

export function clock(epochMillis: number): string {
  const date = new Date(epochMillis);
  return `${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
}

export function clockSeconds(epochMillis: number): string {
  const date = new Date(epochMillis);
  return `${clock(epochMillis)}:${pad(date.getUTCSeconds())}`;
}

export function timestamp(epochMillis: number): string {
  const date = new Date(epochMillis);
  return `${clockSeconds(epochMillis)}.${pad(date.getUTCMilliseconds(), 3)} UTC`;
}

export function duration(millis: number): string {
  if (millis < 1_000) {
    return `${Math.round(millis)} ms`;
  }
  if (millis < 60_000) {
    return `${(millis / 1_000).toFixed(1)} s`;
  }
  if (millis < 3_600_000) {
    return `${Math.floor(millis / 60_000)} min ${Math.floor((millis % 60_000) / 1_000)} s`;
  }
  return `${(millis / 3_600_000).toFixed(1)} h`;
}

export function count(value: number): string {
  return value.toLocaleString('en-US');
}

export const PLACEMENT_LABELS: Record<Placement, string> = {
  SEARCH_RESULTS: 'Search results',
  SEARCH_TAB: 'Search tab',
  TODAY_TAB: 'Today tab',
  PRODUCT_PAGES: 'Product pages',
};

/** Short labels for chips; the full code is always available next to them or in Explain. */
export const REASON_SHORT: Record<ReasonCode, string> = {
  ELIGIBLE: 'Serve',
  DEPENDENCY_UNAVAILABLE: 'State unreadable',
  DEADLINE_EXCEEDED: 'Too slow',
  TRAFFIC_INVALID_USER_AGENT: 'Bot user agent',
  TRAFFIC_RATE_EXCEEDED: 'Rate limit',
  CAMPAIGN_NOT_FOUND: 'Not found',
  CONFIG_STALE: 'Config stale',
  CAMPAIGN_PAUSED: 'Paused',
  POLICY_PLACEMENT_NOT_ALLOWED: 'Placement',
  POLICY_NEGATIVE_KEYWORD: 'Negative keyword',
  SPEND_DATA_STALE: 'Spend stale',
  BUDGET_EXHAUSTED: 'Budget spent',
  PACING_THROTTLED: 'Pacing',
};

/** One plain-English line per reason code. */
export const REASON_PLAIN: Record<ReasonCode, string> = {
  ELIGIBLE: 'Passed every check, so this ad may serve.',
  DEPENDENCY_UNAVAILABLE: 'AdGate could not read the state it needs (Redis or the campaign config), so it refused rather than guess.',
  DEADLINE_EXCEEDED: 'Reading state took longer than the 50 ms budget; a late answer is useless to the ad server.',
  TRAFFIC_INVALID_USER_AGENT: 'The user agent looks like a bot or an automation tool.',
  TRAFFIC_RATE_EXCEEDED: 'This device sent more than 20 requests in 10 seconds, which looks like invalid traffic.',
  CAMPAIGN_NOT_FOUND: 'There is no configuration for this campaign; it may have been deleted.',
  CONFIG_STALE: 'The campaign settings could not be refreshed for over 30 s, so they cannot be trusted.',
  CAMPAIGN_PAUSED: 'The advertiser has paused this campaign.',
  POLICY_PLACEMENT_NOT_ALLOWED: 'The campaign does not run on this placement.',
  POLICY_NEGATIVE_KEYWORD: "The search contains one of the campaign's negative keywords.",
  SPEND_DATA_STALE: 'Spend data is more than 10 s behind, so AdGate cannot prove there is budget left.',
  BUDGET_EXHAUSTED: "Today's budget is used up (0.5% is held back for spend still in flight).",
  PACING_THROTTLED: 'The campaign is ahead of its even-pacing line, so this request is skipped to spread spend over the day.',
};
