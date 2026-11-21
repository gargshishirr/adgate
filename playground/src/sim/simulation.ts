import { dayFraction, evenPacingAllowanceMicros, MILLIS_PER_DAY } from '../engine/pacing';
import { REASON_CODES } from '../engine/reasonCodes';
import { DEFAULT_SETTINGS } from '../engine/settings';
import type { Campaign, EligibilityRequest, Placement, ReasonCode } from '../engine/types';
import { RETIRED_CAMPAIGN_ID, SAMPLE_CAMPAIGNS, type SampleCampaign } from './campaigns';
import { runAuction, winCostMicros } from './market';
import { Rng } from './rng';
import { generateRequest, HUMAN_USER_AGENT } from './traffic';
import { NO_CHAOS, utcDay, World, type ChaosSwitch, type DecisionRecord } from './world';

/**
 * Drives the playground: a simulated clock, live traffic, the decision log behind the feed and Explain, rolling
 * statistics and the day's spend history. UI-agnostic, so it can be tested without a browser.
 */

export const SPEEDS = [1, 60, 600, 3600] as const;
export type Speed = (typeof SPEEDS)[number];

export const RECENT_WINDOW = 300;
const LOG_SIZE = 400;
const HISTORY_STEP_MILLIS = 5 * 60_000;
/** Longest stretch of wall time one tick may cover, so a backgrounded tab does not jump hours ahead. */
const MAX_TICK_WALL_MILLIS = 250;
/** Requests simulated per hour when skipping ahead, and during the warm-up before the visitor arrives. */
const SKIP_REQUESTS_PER_HOUR = 360;
const MAX_REQUESTS_PER_ADVANCE = 20_000;
/**
 * Longest stretch of simulated traffic one live request may stand for. At fast clock speeds more requests are sent
 * instead, so wins stay small and spend tracks the pacing line as closely as it does in real time.
 */
export const MAX_SLICE_MILLIS = 30_000;

export type Milestone = 'sent' | 'traffic' | 'fastForward' | 'chaos' | 'explain';

export interface LoggedDecision extends DecisionRecord {
  readonly seq: number;
  readonly source: 'traffic' | 'you';
}

export interface DevicePreset {
  readonly id: string;
  readonly label: string;
  readonly userAgent: string;
  /** How many requests the device sends at once. */
  readonly burst: number;
}

export const DEVICE_PRESETS: readonly DevicePreset[] = [
  { id: 'iphone', label: 'iPhone · App Store app', userAgent: HUMAN_USER_AGENT, burst: 1 },
  { id: 'ipad', label: 'iPad · App Store app', userAgent: 'AppStore/3.0 iPadOS/19.1', burst: 1 },
  {
    id: 'headless',
    label: 'Headless browser',
    userAgent: 'Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/131.0.0.0 Safari/537.36',
    burst: 1,
  },
  { id: 'crawler', label: 'Search crawler', userAgent: 'Mozilla/5.0 (compatible; ExampleBot/2.1)', burst: 1 },
  { id: 'burst', label: 'Hyperactive device (24 requests at once)', userAgent: HUMAN_USER_AGENT, burst: 24 },
];

export interface ComposedRequest {
  readonly placement: Placement;
  readonly query: string;
  readonly deviceId: DevicePreset['id'];
  readonly campaignIds: readonly string[];
}

export interface HistoryPoint {
  readonly atMillis: number;
  readonly percent: number;
}

export interface SimulationOptions {
  readonly seed?: number;
  /** Midnight UTC of the simulated day. */
  readonly dayStartMillis?: number;
  /** Simulated time of day the visitor arrives at; the morning before it is simulated at start-up. */
  readonly arriveAtMillisOfDay?: number;
}

export class Simulation {
  readonly world: World;
  readonly samples: readonly SampleCampaign[] = SAMPLE_CAMPAIGNS;
  readonly log: LoggedDecision[] = [];
  readonly milestones = new Set<Milestone>();

  clockRunning = true;
  speed: Speed = 60;
  trafficOn = false;
  /** Requests per second of wall time. */
  rate = 12;
  decisionsToday = 0;
  requestsToday = 0;
  /** Bumped on every change, so a UI can tell when to re-render. */
  version = 0;

  private readonly rng: Rng;
  private readonly recent: ReasonCode[] = [];
  private readonly history = new Map<string, HistoryPoint[]>();
  private nextRequestAt = 0;
  private nextHistoryAt = 0;
  private seq = 0;
  private manualCount = 0;
  private readonly listeners = new Set<() => void>();

  constructor(options: SimulationOptions = {}) {
    const dayStart = options.dayStartMillis ?? utcDay(Date.now()) * MILLIS_PER_DAY;
    this.rng = new Rng(options.seed ?? 20_261_119);
    this.world = new World(
      dayStart,
      this.samples.map((sample) => sample.campaign),
      this.rng,
    );
    this.startDay(dayStart);
    const arriveAt = options.arriveAtMillisOfDay ?? 8 * 3_600_000;
    if (arriveAt > 0) {
      this.advanceWithTraffic(arriveAt, MILLIS_PER_DAY / (SKIP_REQUESTS_PER_HOUR * 24));
    }
    this.nextRequestAt = this.world.now;
  }

  get now(): number {
    return this.world.now;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Called once per animation frame with the wall time that passed. */
  tick(wallDeltaMillis: number): void {
    if (!this.clockRunning || wallDeltaMillis <= 0) {
      return;
    }
    const simDelta = Math.min(wallDeltaMillis, MAX_TICK_WALL_MILLIS) * this.speed;
    if (this.trafficOn) {
      this.advanceWithTraffic(simDelta, this.trafficInterval());
    } else {
      this.advanceQuietly(simDelta);
    }
    // Ticks only bump the version; the UI polls it once per frame instead of being notified 60 times a second.
    this.version++;
  }

  setClockRunning(running: boolean): void {
    this.clockRunning = running;
    this.nextRequestAt = this.now + this.trafficInterval();
    this.changed();
  }

  setSpeed(speed: Speed): void {
    this.speed = speed;
    this.nextRequestAt = this.now + this.trafficInterval();
    if (speed >= 600) {
      this.milestones.add('fastForward');
    }
    this.changed();
  }

  setTraffic(on: boolean): void {
    this.trafficOn = on;
    if (on) {
      this.clockRunning = true;
      this.milestones.add('traffic');
    }
    this.nextRequestAt = this.now + this.trafficInterval();
    this.changed();
  }

  setRate(rate: number): void {
    this.rate = Math.max(1, Math.min(100, Math.round(rate)));
    this.nextRequestAt = this.now + this.trafficInterval();
    this.changed();
  }

  /** Fast-forwards the clock by the given simulated time, with traffic flowing meanwhile. */
  skip(simMillis: number): void {
    this.advanceWithTraffic(simMillis, 3_600_000 / SKIP_REQUESTS_PER_HOUR);
    this.nextRequestAt = this.now + this.trafficInterval();
    this.milestones.add('fastForward');
    this.changed();
  }

  setChaos(name: ChaosSwitch, on: boolean): void {
    this.world.setChaos({ ...this.world.chaos, [name]: on });
    if (on) {
      this.milestones.add('chaos');
    }
    this.changed();
  }

  recoverAll(): void {
    this.world.setChaos(NO_CHAOS);
    this.changed();
  }

  updateCampaign(campaign: Campaign): void {
    this.world.upsertCampaign(campaign);
    this.changed();
  }

  /** Publishes an auction win to the spend pipeline (it reaches the ledger only if the pipeline is running). */
  injectSpend(campaignId: string, micros: number): void {
    this.world.recordSpend(campaignId, micros);
    this.changed();
  }

  markExplained(): void {
    if (!this.milestones.has('explain')) {
      this.milestones.add('explain');
      this.changed();
    }
  }

  /** Sends the composed request (or a burst of them) through the engine. Returns the last decision. */
  send(composed: ComposedRequest): LoggedDecision {
    const preset = DEVICE_PRESETS.find((device) => device.id === composed.deviceId) ?? DEVICE_PRESETS[0]!;
    const deviceId = preset.burst > 1 ? `device-burst-${this.manualCount + 1}` : `device-${preset.id}`;
    let last: LoggedDecision | null = null;
    for (let i = 0; i < preset.burst; i++) {
      this.manualCount++;
      const request: EligibilityRequest = {
        requestId: `you-${String(this.manualCount).padStart(4, '0')}`,
        placement: composed.placement,
        query: composed.query,
        device: { id: deviceId, userAgent: preset.userAgent },
        candidates: composed.campaignIds.map((campaignId) => ({ adId: `ad-${campaignId}`, campaignId })),
      };
      last = this.record(this.world.decide(request), 'you');
    }
    this.milestones.add('sent');
    this.changed();
    return last!;
  }

  find(requestId: string): LoggedDecision | undefined {
    return this.log.find((entry) => entry.request.requestId === requestId);
  }

  /** The same request against current state, without counting it (replay?currentState=true). */
  evaluateNow(request: EligibilityRequest): DecisionRecord {
    return this.world.decide(request, false);
  }

  /** Reason counts over the last RECENT_WINDOW candidate decisions, in reason-code order. */
  recentReasons(): { code: ReasonCode; count: number }[] {
    const counts = new Map<ReasonCode, number>();
    for (const code of this.recent) {
      counts.set(code, (counts.get(code) ?? 0) + 1);
    }
    return REASON_CODES.map((info) => ({ code: info.code, count: counts.get(info.code) ?? 0 }));
  }

  recentTotal(): number {
    return this.recent.length;
  }

  spendHistory(campaignId: string): readonly HistoryPoint[] {
    return this.history.get(campaignId) ?? [];
  }

  /** Share of the budget even pacing allows at this moment, in percent. */
  pacingLinePercent(atMillis = this.now): number {
    return (
      evenPacingAllowanceMicros(1_000_000_000, dayFraction(atMillis), DEFAULT_SETTINGS.pacingBurst, DEFAULT_SETTINGS.pacingEarlyDayAllowance) /
      10_000_000
    );
  }

  /** Requests per second of wall time that live traffic sends: the chosen rate, raised at fast clock speeds. */
  effectiveRate(): number {
    return Math.round((this.speed * 1000) / this.trafficInterval());
  }

  private trafficInterval(): number {
    return Math.min((this.speed * 1000) / this.rate, MAX_SLICE_MILLIS);
  }

  private advanceQuietly(simDelta: number): void {
    this.moveTo(this.now + simDelta);
  }

  private advanceWithTraffic(simDelta: number, intervalMillis: number): void {
    const target = this.now + simDelta;
    let produced = 0;
    if (this.nextRequestAt < this.now) {
      this.nextRequestAt = this.now;
    }
    while (this.nextRequestAt <= target && produced < MAX_REQUESTS_PER_ADVANCE) {
      this.moveTo(this.nextRequestAt);
      this.serveTraffic(intervalMillis);
      this.nextRequestAt += intervalMillis;
      produced++;
    }
    this.moveTo(target);
  }

  private moveTo(target: number): void {
    while (utcDay(target) !== utcDay(this.now)) {
      const midnight = (utcDay(this.now) + 1) * MILLIS_PER_DAY;
      this.advanceWorld(midnight);
      this.startDay(midnight);
    }
    this.advanceWorld(target);
  }

  private advanceWorld(target: number): void {
    this.world.advanceTo(target);
    while (this.nextHistoryAt <= target) {
      this.sampleHistory(this.nextHistoryAt);
      this.nextHistoryAt += HISTORY_STEP_MILLIS;
    }
  }

  private startDay(midnight: number): void {
    this.history.clear();
    this.decisionsToday = 0;
    this.requestsToday = 0;
    this.nextHistoryAt = midnight;
    this.world.advanceTo(midnight);
    this.sampleHistory(midnight);
    this.nextHistoryAt = midnight + HISTORY_STEP_MILLIS;
  }

  private sampleHistory(atMillis: number): void {
    for (const sample of this.samples) {
      const campaign = this.world.campaign(sample.campaign.id);
      if (!campaign) {
        continue;
      }
      const points = this.history.get(campaign.id) ?? [];
      points.push({ atMillis, percent: (100 * this.world.spentToday(campaign.id)) / campaign.dailyBudgetMicros });
      this.history.set(campaign.id, points);
    }
  }

  private serveTraffic(sliceMillis: number): void {
    const ids = this.samples.map((sample) => sample.campaign.id);
    const request = generateRequest(this.rng, ids, RETIRED_CAMPAIGN_ID);
    const record = this.record(this.world.decide(request), 'traffic');
    const eligible = record.decisions.filter((decision) => decision.eligible).map((decision) => decision.campaignId);
    const winner = runAuction(eligible, this.rng);
    if (winner !== null) {
      const sample = this.samples.find((candidate) => candidate.campaign.id === winner);
      const campaign = this.world.campaign(winner);
      if (sample && campaign) {
        this.world.recordSpend(
          winner,
          winCostMicros({ dailyBudgetMicros: campaign.dailyBudgetMicros, demandHours: sample.demandHours }, sliceMillis, this.rng),
        );
      }
    }
  }

  private record(record: DecisionRecord, source: LoggedDecision['source']): LoggedDecision {
    const logged: LoggedDecision = { ...record, seq: ++this.seq, source };
    this.log.unshift(logged);
    if (this.log.length > LOG_SIZE) {
      this.log.length = LOG_SIZE;
    }
    for (const decision of record.decisions) {
      this.recent.push(decision.reason);
    }
    if (this.recent.length > RECENT_WINDOW) {
      this.recent.splice(0, this.recent.length - RECENT_WINDOW);
    }
    this.requestsToday++;
    this.decisionsToday += record.decisions.length;
    return logged;
  }

  private changed(): void {
    this.version++;
    for (const listener of this.listeners) {
      listener();
    }
  }
}
