import { useRef, useState } from 'preact/hooks';

import { MILLIS_PER_DAY } from '../engine/pacing';
import { reasonInfo } from '../engine/reasonCodes';
import { MAX_SLICE_MILLIS, RECENT_WINDOW, SPEEDS, type LoggedDecision, type Simulation, type Speed } from '../sim/simulation';
import { Panel, Segmented, toneOf } from './common';
import { clock, clockSeconds, count, PLACEMENT_LABELS, REASON_SHORT } from './format';
import { IconForward, IconLock, IconPause, IconPlay } from './icons';

const SPEED_LABELS: Record<Speed, string> = { 1: 'Real time', 60: '1 min/s', 600: '10 min/s', 3600: '1 h/s' };

/** The simulation's transport: clock, traffic, rate and fast-forward. */
export function Transport({ sim }: { sim: Simulation }) {
  const dayProgress = ((sim.now % MILLIS_PER_DAY) / MILLIS_PER_DAY) * 100;
  const effectiveRate = sim.effectiveRate();
  const boosted = effectiveRate > sim.rate;
  return (
    <section class="transport" aria-label="Simulation controls">
      <div class="transport__clock">
        <div class="clockface">
          <span class="clockface__time">{clock(sim.now)}</span>
          <span class="clockface__zone">UTC</span>
        </div>
        <div class="clockbar__day">
          <div class="daybar" role="img" aria-label={`Simulated clock ${clock(sim.now)} UTC; ${dayProgress.toFixed(0)}% of the day has passed`}>
            <div class="daybar__fill" style={{ width: `${dayProgress}%` }} />
          </div>
          <div class="daybar__labels" aria-hidden="true">
            <span>00:00</span>
            <span>12:00</span>
            <span>24:00</span>
          </div>
        </div>
        <button
          type="button"
          class="icon-button"
          onClick={() => sim.setClockRunning(!sim.clockRunning)}
          aria-label={sim.clockRunning ? 'Pause the simulated clock' : 'Resume the simulated clock'}
          title={sim.clockRunning ? 'Pause the clock' : 'Resume the clock'}
        >
          {sim.clockRunning ? <IconPause size={16} /> : <IconPlay size={16} />}
        </button>
      </div>
      <div class="transport__traffic">
        <button
          type="button"
          id="traffic-toggle"
          class={`button ${sim.trafficOn ? 'button--secondary' : 'button--primary'}`}
          aria-pressed={sim.trafficOn}
          onClick={() => sim.setTraffic(!sim.trafficOn)}
        >
          {sim.trafficOn ? <IconPause size={16} /> : <IconPlay size={16} />}
          {sim.trafficOn ? 'Pause traffic' : 'Start traffic'}
        </button>
        <label class="rate">
          <span class="rate__label">
            <span>
              <strong>{effectiveRate}</strong> req/s
            </span>
            {boosted ? <span class="rate__boost">raised for {SPEED_LABELS[sim.speed]}</span> : null}
          </span>
          <input
            type="range"
            min={1}
            max={60}
            step={1}
            value={sim.rate}
            aria-label="Requests per second"
            aria-valuetext={`${sim.rate} requests per second${boosted ? `, raised to ${effectiveRate} at this clock speed` : ''}`}
            title={
              boosted
                ? `At ${SPEED_LABELS[sim.speed]} each request stands for at most ${MAX_SLICE_MILLIS / 1000} s of traffic, so more requests are sent`
                : undefined
            }
            onInput={(event) => sim.setRate(Number(event.currentTarget.value))}
          />
        </label>
      </div>
      <div class="transport__speed" id="speed-control">
        <Segmented
          label="Clock speed"
          value={sim.speed}
          options={SPEEDS.map((speed) => ({ value: speed, label: SPEED_LABELS[speed] }))}
          onChange={(speed) => sim.setSpeed(speed)}
        />
        <button type="button" class="button button--ghost" onClick={() => sim.skip(3_600_000)}>
          <IconForward size={15} />
          Skip 1 h
        </button>
      </div>
    </section>
  );
}

export function TrafficPanel({ sim, onExplain }: { sim: Simulation; onExplain: (requestId: string) => void }) {
  return (
    <Panel id="traffic" title="Live traffic" kicker="Simulated ad server">
      <SpendChart sim={sim} />
      <ReasonChart sim={sim} />
      <Feed sim={sim} onExplain={onExplain} />
    </Panel>
  );
}

const CHART_W = 600;
const CHART_H = 220;

function SpendChart({ sim }: { sim: Simulation }) {
  const dayStart = Math.floor(sim.now / MILLIS_PER_DAY) * MILLIS_PER_DAY;
  const x = (at: number) => ((at - dayStart) / MILLIS_PER_DAY) * CHART_W;
  const y = (pct: number) => CHART_H - (Math.min(105, Math.max(0, pct)) / 105) * CHART_H;
  const pacing: string[] = [];
  for (let minute = 0; minute <= 24 * 60; minute += 10) {
    const at = dayStart + Math.min(minute * 60_000, MILLIS_PER_DAY - 1);
    pacing.push(`${x(at).toFixed(1)},${y(sim.pacingLinePercent(at)).toFixed(1)}`);
  }
  const nowX = x(sim.now);
  return (
    <figure class="chart">
      <figcaption class="chart__title">
        Spend vs. the even-pacing line, today
        <span class="chart__sub">share of each daily budget</span>
      </figcaption>
      <div class="chart__plot">
        <svg viewBox={`0 0 ${CHART_W} ${CHART_H}`} preserveAspectRatio="none" role="img" aria-label={spendSummary(sim)}>
          {[0, 25, 50, 75, 100].map((pct) => (
            <line key={pct} x1="0" x2={CHART_W} y1={y(pct)} y2={y(pct)} class="chart__grid" vector-effect="non-scaling-stroke" />
          ))}
          {sim.samples.map((sample) => {
            const points = sim.spendHistory(sample.campaign.id).map((point) => `${x(point.atMillis).toFixed(1)},${y(point.percent).toFixed(1)}`);
            const current = (100 * sim.world.spentToday(sample.campaign.id)) / (sim.world.campaign(sample.campaign.id)?.dailyBudgetMicros ?? 1);
            points.push(`${nowX.toFixed(1)},${y(current).toFixed(1)}`);
            return (
              <polyline
                key={sample.campaign.id}
                points={points.join(' ')}
                fill="none"
                stroke={sample.color}
                class="chart__series"
                vector-effect="non-scaling-stroke"
              />
            );
          })}
          <polyline points={pacing.join(' ')} class="chart__pacing" vector-effect="non-scaling-stroke" />
          <line x1={nowX} x2={nowX} y1="0" y2={CHART_H} class="chart__now" vector-effect="non-scaling-stroke" />
        </svg>
        <div class="chart__yaxis" aria-hidden="true">
          {[100, 50, 0].map((pct) => (
            <span key={pct} style={{ top: `${(y(pct) / CHART_H) * 100}%` }}>
              {pct}%
            </span>
          ))}
        </div>
      </div>
      <div class="chart__xaxis" aria-hidden="true">
        <span>00:00</span>
        <span>06:00</span>
        <span>12:00</span>
        <span>18:00</span>
        <span>24:00</span>
      </div>
      <ul class="legend">
        <li>
          <span class="legend__swatch legend__swatch--pacing" aria-hidden="true" />
          Even-pacing line
        </li>
        {sim.samples.map((sample) => (
          <li key={sample.campaign.id}>
            <span class="legend__swatch" style={{ background: sample.color }} aria-hidden="true" />
            {sample.name}
            {sim.world.campaign(sample.campaign.id)?.pacing === 'ASAP' ? <span class="muted"> (ASAP)</span> : null}
          </li>
        ))}
      </ul>
    </figure>
  );
}

function spendSummary(sim: Simulation): string {
  const parts = sim.samples.map((sample) => {
    const campaign = sim.world.campaign(sample.campaign.id);
    const pct = campaign ? (100 * sim.world.spentToday(campaign.id)) / campaign.dailyBudgetMicros : 0;
    return `${sample.name} ${pct.toFixed(1)}%`;
  });
  return `Spend so far today: ${parts.join(', ')}. The even-pacing line is at ${sim.pacingLinePercent().toFixed(1)}%.`;
}

function ReasonChart({ sim }: { sim: Simulation }) {
  const rows = sim.recentReasons();
  const total = sim.recentTotal();
  const max = Math.max(1, ...rows.map((row) => row.count));
  return (
    <figure class="bars">
      <figcaption class="chart__title">
        Decisions by reason
        <span class="chart__sub">
          last {count(Math.min(total, RECENT_WINDOW))} candidate decisions · {count(sim.decisionsToday)} today
        </span>
      </figcaption>
      <ul class="bars__list">
        {rows.map((row) => {
          const tone = toneOf(row.code);
          const share = total === 0 ? 0 : (100 * row.count) / total;
          return (
            <li key={row.code} class={`bars__row ${row.count === 0 ? 'bars__row--zero' : ''}`}>
              <span class="bars__label">
                <code>{row.code}</code>
                {reasonInfo(row.code).failClosed ? (
                  <span class="bars__fc" title="Fail-closed: AdGate could not prove it was safe to serve">
                    <IconLock size={11} />
                    <span class="visually-hidden">fail-closed</span>
                  </span>
                ) : null}
              </span>
              <span class="bars__track" aria-hidden="true">
                <span class={`bars__fill bars__fill--${tone}`} style={{ width: `${(row.count / max) * 100}%` }} />
              </span>
              <span class="bars__value">
                {count(row.count)}
                <span class="visually-hidden"> decisions, {share.toFixed(0)}%</span>
              </span>
            </li>
          );
        })}
      </ul>
    </figure>
  );
}

function Feed({ sim, onExplain }: { sim: Simulation; onExplain: (requestId: string) => void }) {
  const [frozen, setFrozen] = useState<LoggedDecision[] | null>(null);
  const live = sim.log.slice(0, 10);
  const items = frozen ?? live;
  const listRef = useRef<HTMLUListElement>(null);
  // Hold the list still while a mouse or keyboard user is inside it, so a moving item can be clicked.
  const freeze = (event: PointerEvent | FocusEvent) => {
    if (event instanceof PointerEvent && event.pointerType !== 'mouse') {
      return;
    }
    setFrozen((current) => current ?? sim.log.slice(0, 10));
  };
  const unfreeze = (event: FocusEvent | PointerEvent) => {
    const next = (event as FocusEvent).relatedTarget as Node | null;
    if (event.type === 'focusout' && next && listRef.current?.contains(next)) {
      return;
    }
    setFrozen(null);
  };
  const nameOf = (id: string) => sim.samples.find((sample) => sample.campaign.id === id)?.name ?? id;

  return (
    <div class="feed">
      <div class="chart__title">
        Recent decisions
        <span class="chart__sub">{frozen ? 'paused while you point at it' : 'newest first · click one to explain it'}</span>
      </div>
      <ul
        class="feed__list"
        id="feed-list"
        ref={listRef}
        onPointerEnter={freeze}
        onPointerLeave={unfreeze}
        onFocusIn={freeze}
        onFocusOut={unfreeze}
      >
        {items.length === 0 ? <li class="muted feed__empty">No decisions yet. Send a request or start traffic.</li> : null}
        {items.map((entry) => (
          <li key={entry.seq}>
            <button type="button" class="feed__item" onClick={() => onExplain(entry.request.requestId)}>
              <span class="feed__meta">
                <span class="feed__time">{clockSeconds(entry.state.nowMillis)}</span>
                <span class="feed__query">
                  {entry.source === 'you' ? <span class="pill pill--you">You</span> : null}
                  {PLACEMENT_LABELS[entry.request.placement]} · “{entry.request.query}”
                </span>
              </span>
              <span class="feed__decisions">
                {entry.decisions.map((decision) => (
                  <span key={decision.adId} class={`mini mini--${toneOf(decision.reason)}`} title={`${nameOf(decision.campaignId)}: ${decision.reason}`}>
                    <span class="mini__name">{nameOf(decision.campaignId)}</span>
                    <span class="mini__reason">{REASON_SHORT[decision.reason]}</span>
                  </span>
                ))}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
