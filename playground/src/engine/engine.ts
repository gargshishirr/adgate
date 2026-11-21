import { formatFixed, hourMinuteUtc, isJavaBlank } from './javaFormat';
import { dayFraction, evenPacingAllowanceMicros } from './pacing';
import { containsPhrase, tokens } from './queryNormalizer';
import { unit } from './sampler';
import { DEFAULT_SETTINGS } from './settings';
import type {
  Candidate,
  CampaignState,
  Decision,
  EligibilityRequest,
  EngineSettings,
  ReasonCode,
  ServingState,
} from './types';

/**
 * Port of EligibilityEngine.java. Decides, for every candidate of an ad request, whether it may serve.
 *
 * The engine is a pure function of the request and a ServingState snapshot: no I/O, no clock. Checks run in a
 * fixed order and the first failing check is the reason reported. Missing or stale inputs lead to a decline with
 * a fail-closed reason code, never to an optimistic serve.
 *
 * The optional trace only observes the evaluation (for the playground's Explain panel); it never changes it.
 */

/** The checks, in the order the engine runs them, with the reason reported when each one fails. */
export const CHECKS = [
  { id: 'dependency', label: 'Serving state readable', reason: 'DEPENDENCY_UNAVAILABLE', scope: 'request' },
  { id: 'deadline', label: 'Within the latency budget', reason: 'DEADLINE_EXCEEDED', scope: 'request' },
  { id: 'userAgent', label: 'User agent is not automation', reason: 'TRAFFIC_INVALID_USER_AGENT', scope: 'request' },
  { id: 'deviceRate', label: 'Device under its request rate', reason: 'TRAFFIC_RATE_EXCEEDED', scope: 'request' },
  { id: 'configLoaded', label: 'Campaign configuration loaded', reason: 'DEPENDENCY_UNAVAILABLE', scope: 'candidate' },
  { id: 'configExists', label: 'Campaign configuration exists', reason: 'CAMPAIGN_NOT_FOUND', scope: 'candidate' },
  { id: 'configFresh', label: 'Configuration is fresh', reason: 'CONFIG_STALE', scope: 'candidate' },
  { id: 'active', label: 'Campaign is active', reason: 'CAMPAIGN_PAUSED', scope: 'candidate' },
  { id: 'placement', label: 'Placement is allowed', reason: 'POLICY_PLACEMENT_NOT_ALLOWED', scope: 'candidate' },
  { id: 'negativeKeywords', label: 'No negative keyword in the query', reason: 'POLICY_NEGATIVE_KEYWORD', scope: 'candidate' },
  { id: 'spendFresh', label: 'Spend data is fresh', reason: 'SPEND_DATA_STALE', scope: 'candidate' },
  { id: 'budget', label: 'Budget remains', reason: 'BUDGET_EXHAUSTED', scope: 'candidate' },
  { id: 'pacing', label: 'Within the even-pacing schedule', reason: 'PACING_THROTTLED', scope: 'candidate' },
] as const satisfies readonly { id: string; label: string; reason: ReasonCode; scope: 'request' | 'candidate' }[];

export type CheckId = (typeof CHECKS)[number]['id'];

export type CheckOutcome = 'pass' | 'fail' | 'skip';

export interface CheckResult {
  readonly id: CheckId;
  /** 'skip' means the check does not apply (pacing for an ASAP campaign). */
  readonly outcome: CheckOutcome;
  readonly note: string;
}

export interface CandidateTrace {
  readonly candidate: Candidate;
  /** The checks that ran, in order. Evaluation stops at the first failure, so later checks are absent. */
  readonly checks: readonly CheckResult[];
  readonly decision: Decision;
}

export interface TracedEvaluation {
  readonly decisions: readonly Decision[];
  readonly traces: readonly CandidateTrace[];
}

type Tracer = ((id: CheckId, outcome: CheckOutcome, note: () => string) => void) | null;

export function evaluate(
  request: EligibilityRequest,
  state: ServingState,
  settings: EngineSettings = DEFAULT_SETTINGS,
): Decision[] {
  return run(request, state, settings, () => null);
}

/** Same evaluation as {@link evaluate}, plus a record of every check that ran for every candidate. */
export function evaluateTraced(
  request: EligibilityRequest,
  state: ServingState,
  settings: EngineSettings = DEFAULT_SETTINGS,
): TracedEvaluation {
  const requestChecks: CheckResult[] = [];
  const candidateChecks = new Map<number, CheckResult[]>();
  const decisions = run(request, state, settings, (index) => (id, outcome, note) => {
    const target = index === null ? requestChecks : candidateChecks.get(index) ?? [];
    target.push({ id, outcome, note: note() });
    if (index !== null) {
      candidateChecks.set(index, target);
    }
  });
  const traces = request.candidates.map((candidate, index) => ({
    candidate,
    checks: [...requestChecks, ...(candidateChecks.get(index) ?? [])],
    decision: decisions[index]!,
  }));
  return { decisions, traces };
}

function run(
  request: EligibilityRequest,
  state: ServingState,
  settings: EngineSettings,
  tracerFor: (candidateIndex: number | null) => Tracer,
): Decision[] {
  const candidates = request.candidates;
  const trace = tracerFor(null);

  switch (state.dependencyStatus) {
    case 'OK':
      trace?.('dependency', 'pass', () => 'one pipelined Redis read returned spend, watermarks and the device counter');
      trace?.('deadline', 'pass', () => 'state arrived within the latency budget');
      break;
    case 'DEADLINE_EXCEEDED':
      trace?.('dependency', 'pass', () => 'state was read, but too late');
      trace?.('deadline', 'fail', () => String(state.dependencyDetail));
      return declineAll(candidates, 'DEADLINE_EXCEEDED', state.dependencyDetail);
    default: {
      const detail = `${state.dependencyStatus}: ${state.dependencyDetail}`;
      trace?.('dependency', 'fail', () => detail);
      return declineAll(candidates, 'DEPENDENCY_UNAVAILABLE', detail);
    }
  }

  const userAgent = request.device.userAgent;
  const blockedToken = blockedUserAgentToken(userAgent, settings);
  if (blockedToken !== null) {
    trace?.('userAgent', 'fail', () => `"${userAgent}" contains '${blockedToken}'`);
    return declineAll(candidates, 'TRAFFIC_INVALID_USER_AGENT', `user agent contains '${blockedToken}'`);
  }
  trace?.('userAgent', 'pass', () =>
    userAgent === null || isJavaBlank(userAgent) ? 'no user agent sent' : `no blocked token in "${userAgent}"`,
  );

  const windowSeconds = Math.floor(settings.deviceRateWindowMillis / 1000);
  if (state.deviceRequestsInWindow > settings.deviceRequestLimit) {
    const detail = `device made ${state.deviceRequestsInWindow} requests in a ${windowSeconds}s window (limit ${settings.deviceRequestLimit})`;
    trace?.('deviceRate', 'fail', () => detail);
    return declineAll(candidates, 'TRAFFIC_RATE_EXCEEDED', detail);
  }
  trace?.('deviceRate', 'pass', () =>
    `${state.deviceRequestsInWindow} of ${settings.deviceRequestLimit} requests allowed in this ${windowSeconds}s window`,
  );

  const queryTokens = tokens(request.query);
  return candidates.map((candidate, index) =>
    evaluateCandidate(request, state, candidate, queryTokens, settings, tracerFor(index)),
  );
}

function evaluateCandidate(
  request: EligibilityRequest,
  state: ServingState,
  candidate: Candidate,
  queryTokens: readonly string[],
  settings: EngineSettings,
  trace: Tracer,
): Decision {
  const campaignState: CampaignState | undefined = Object.hasOwn(state.campaigns, candidate.campaignId)
    ? state.campaigns[candidate.campaignId]
    : undefined;
  if (campaignState === undefined || campaignState.configStatus === 'UNAVAILABLE') {
    trace?.('configLoaded', 'fail', () => 'the configuration could not be loaded and was not cached');
    return decline(candidate, 'DEPENDENCY_UNAVAILABLE', 'campaign configuration could not be loaded');
  }
  trace?.('configLoaded', 'pass', () => 'loaded from the local configuration cache');
  if (campaignState.configStatus === 'NOT_FOUND') {
    trace?.('configExists', 'fail', () => `no configuration for ${candidate.campaignId}`);
    return decline(candidate, 'CAMPAIGN_NOT_FOUND', `no configuration for campaign ${candidate.campaignId}`);
  }
  trace?.('configExists', 'pass', () => `configuration found for ${candidate.campaignId}`);

  const configAgeMillis = state.nowMillis - campaignState.configLoadedAtMillis;
  if (configAgeMillis > settings.maxConfigAgeMillis) {
    const detail = `configuration is ${configAgeMillis} ms old (limit ${settings.maxConfigAgeMillis} ms)`;
    trace?.('configFresh', 'fail', () => detail);
    return decline(candidate, 'CONFIG_STALE', detail);
  }
  trace?.('configFresh', 'pass', () => `loaded ${configAgeMillis} ms ago (limit ${settings.maxConfigAgeMillis} ms)`);

  const campaign = campaignState.campaign;
  if (campaign === null) {
    // Java would throw a NullPointerException here: FOUND always comes with a configuration.
    throw new Error(`Campaign ${candidate.campaignId} is FOUND but has no configuration`);
  }
  if (campaign.status === 'PAUSED') {
    trace?.('active', 'fail', () => 'status is PAUSED');
    return decline(candidate, 'CAMPAIGN_PAUSED', 'campaign is paused');
  }
  trace?.('active', 'pass', () => 'status is ACTIVE');

  if (campaign.placements.length > 0 && !campaign.placements.includes(request.placement)) {
    trace?.('placement', 'fail', () => `${request.placement} is not in [${campaign.placements.join(', ')}]`);
    return decline(candidate, 'POLICY_PLACEMENT_NOT_ALLOWED', `campaign does not serve on ${request.placement}`);
  }
  trace?.('placement', 'pass', () =>
    campaign.placements.length === 0 ? 'campaign serves on every placement' : `${request.placement} is allowed`,
  );

  for (const keyword of campaign.negativeKeywords) {
    if (containsPhrase(queryTokens, tokens(keyword))) {
      trace?.('negativeKeywords', 'fail', () => `query tokens [${queryTokens.join(' ')}] contain "${tokens(keyword).join(' ')}"`);
      return decline(candidate, 'POLICY_NEGATIVE_KEYWORD', `query matches negative keyword '${keyword}'`);
    }
  }
  trace?.('negativeKeywords', 'pass', () =>
    campaign.negativeKeywords.length === 0
      ? 'campaign has no negative keywords'
      : `query tokens [${queryTokens.join(' ')}] match none of ${campaign.negativeKeywords.map((k) => `"${k}"`).join(', ')}`,
  );

  const watermark = campaignState.spendWatermarkMillis;
  if (watermark === null) {
    const detail = `no spend watermark yet for partition ${campaignState.spendPartition}`;
    trace?.('spendFresh', 'fail', () => detail);
    return decline(candidate, 'SPEND_DATA_STALE', detail);
  }
  const spendLagMillis = state.nowMillis - watermark;
  if (spendLagMillis > settings.maxSpendStalenessMillis) {
    const detail = `spend for partition ${campaignState.spendPartition} is ${spendLagMillis} ms behind (limit ${settings.maxSpendStalenessMillis} ms)`;
    trace?.('spendFresh', 'fail', () => detail);
    return decline(candidate, 'SPEND_DATA_STALE', detail);
  }
  trace?.('spendFresh', 'pass', () =>
    `partition ${campaignState.spendPartition} is ${spendLagMillis} ms behind (limit ${settings.maxSpendStalenessMillis} ms)`,
  );

  const budget = campaign.dailyBudgetMicros;
  const spent = campaignState.spentTodayMicros;
  const exhaustedAt = budget - Math.ceil(budget * settings.budgetSafetyMargin);
  if (spent >= exhaustedAt) {
    trace?.('budget', 'fail', () => `spent $${money(spent)} ≥ $${money(exhaustedAt)}, the budget minus the safety margin for spend in flight`);
    return decline(candidate, 'BUDGET_EXHAUSTED', `spent ${money(spent)} of a ${money(budget)} daily budget`);
  }
  trace?.('budget', 'pass', () => `spent $${money(spent)} of $${money(budget)}; serving stops at $${money(exhaustedAt)}`);

  if (campaign.pacing === 'EVEN') {
    const throttled = evenPacing(request, state, candidate, budget, spent, settings, trace);
    if (throttled !== null) {
      return throttled;
    }
  } else {
    trace?.('pacing', 'skip', () => 'ASAP campaigns are not paced');
  }
  return eligible(candidate, `spent ${formatFixed(percent(spent, budget), 1)}% of daily budget`);
}

/** Returns a throttle decision, or null if the campaign is within its even-pacing schedule. */
function evenPacing(
  request: EligibilityRequest,
  state: ServingState,
  candidate: Candidate,
  budget: number,
  spent: number,
  settings: EngineSettings,
  trace: Tracer,
): Decision | null {
  const fraction = dayFraction(state.nowMillis);
  const allowance = evenPacingAllowanceMicros(budget, fraction, settings.pacingBurst, settings.pacingEarlyDayAllowance);
  const headroom = allowance - spent;
  const schedule = `spent ${formatFixed(percent(spent, budget), 1)}% of budget; even pacing allows ${formatFixed(
    percent(allowance, budget),
    1,
  )}% by ${hourMinuteUtc(state.nowMillis)}`;
  if (headroom <= 0) {
    trace?.('pacing', 'fail', () => schedule);
    return decline(candidate, 'PACING_THROTTLED', schedule);
  }
  // Near the pacing line, thin out traffic gradually instead of switching delivery off and on.
  const ramp = Math.trunc(budget * settings.pacingRampFraction);
  if (headroom < ramp) {
    const serveProbability = headroom / ramp;
    const draw = unit(request.requestId, candidate.campaignId);
    if (draw >= serveProbability) {
      const detail = `${schedule}; near the pacing line, serving ${formatFixed(serveProbability * 100, 0)}% of requests`;
      trace?.('pacing', 'fail', () => `${detail} (deterministic draw ${draw.toFixed(3)} ≥ ${serveProbability.toFixed(3)})`);
      return decline(candidate, 'PACING_THROTTLED', detail);
    }
    trace?.('pacing', 'pass', () =>
      `${schedule}; near the line, serving ${formatFixed(serveProbability * 100, 0)}% of requests (deterministic draw ${draw.toFixed(3)} < ${serveProbability.toFixed(3)})`,
    );
    return null;
  }
  trace?.('pacing', 'pass', () => schedule);
  return null;
}

function blockedUserAgentToken(userAgent: string | null, settings: EngineSettings): string | null {
  if (userAgent === null || isJavaBlank(userAgent)) {
    return null;
  }
  const normalized = userAgent.toLowerCase();
  for (const token of settings.blockedUserAgentTokens) {
    if (normalized.includes(token.toLowerCase())) {
      return token;
    }
  }
  return null;
}

function declineAll(candidates: readonly Candidate[], reason: ReasonCode, detail: string | null): Decision[] {
  return candidates.map((candidate) => decline(candidate, reason, detail));
}

function decline(candidate: Candidate, reason: ReasonCode, detail: string | null): Decision {
  if (reason === 'ELIGIBLE') {
    throw new Error('A declined decision needs a decline reason');
  }
  return { adId: candidate.adId, campaignId: candidate.campaignId, eligible: false, reason, detail };
}

function eligible(candidate: Candidate, detail: string): Decision {
  return { adId: candidate.adId, campaignId: candidate.campaignId, eligible: true, reason: 'ELIGIBLE', detail };
}

function percent(part: number, whole: number): number {
  return whole === 0 ? 0 : (100.0 * part) / whole;
}

function money(micros: number): string {
  return formatFixed(micros / 1_000_000.0, 2);
}
