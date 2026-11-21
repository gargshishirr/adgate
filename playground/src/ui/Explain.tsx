import { useEffect, useMemo, useRef, useState } from 'preact/hooks';

import { CHECKS, evaluate, evaluateTraced, type CheckResult } from '../engine/engine';
import { tokens } from '../engine/queryNormalizer';
import { DEFAULT_SETTINGS } from '../engine/settings';
import type { Decision } from '../engine/types';
import type { LoggedDecision, Simulation } from '../sim/simulation';
import { CampaignMark, ReasonChip, toneOf, VerdictBadge } from './common';
import { duration, money, PLACEMENT_LABELS, REASON_PLAIN, timestamp } from './format';
import { IconCheck, IconLock, IconReplay, IconX } from './icons';

interface ReplayOutcome {
  readonly kind: 'snapshot' | 'current';
  readonly decisions: readonly Decision[];
  readonly identical: boolean;
}

export function ExplainDrawer(props: { sim: Simulation; entry: LoggedDecision; initialAdId?: string; onClose: () => void }) {
  const { sim, entry, onClose } = props;
  const traced = useMemo(() => evaluateTraced(entry.request, entry.state), [entry]);
  const queryTokens = useMemo(() => tokens(entry.request.query), [entry]);
  const firstIndex = Math.max(0, entry.request.candidates.findIndex((candidate) => candidate.adId === props.initialAdId));
  const [selected, setSelected] = useState(firstIndex);
  const [replay, setReplay] = useState<ReplayOutcome | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
      } else if (event.key === 'Tab') {
        trapFocus(event, dialogRef.current);
      }
    };
    document.addEventListener('keydown', onKey);
    document.body.classList.add('has-drawer');
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.classList.remove('has-drawer');
      previous?.focus?.();
    };
  }, [onClose]);

  useEffect(() => {
    setSelected(firstIndex);
    setReplay(null);
  }, [entry, firstIndex]);

  const trace = traced.traces[selected] ?? traced.traces[0]!;
  const sampleOf = (id: string) => sim.samples.find((sample) => sample.campaign.id === id);
  const replaySnapshot = () => {
    const decisions = evaluate(entry.request, entry.state);
    setReplay({ kind: 'snapshot', decisions, identical: JSON.stringify(decisions) === JSON.stringify(entry.decisions) });
  };
  const replayCurrent = () => {
    const decisions = sim.evaluateNow(entry.request).decisions;
    // Spend and clocks have moved on, so detail strings differ; compare the verdicts.
    const reasons = (list: readonly Decision[]) => list.map((decision) => decision.reason).join();
    setReplay({ kind: 'current', decisions, identical: reasons(decisions) === reasons(entry.decisions) });
  };

  return (
    <div class="drawer" role="presentation">
      <div class="drawer__scrim" onClick={onClose} />
      <div class="drawer__panel" role="dialog" aria-modal="true" aria-labelledby="explain-title" ref={dialogRef}>
        <header class="drawer__header">
          <div>
            <p class="panel__kicker">Explain · like GET /v1/decisions/{'{id}'}</p>
            <h2 class="drawer__title" id="explain-title">
              <code>{entry.request.requestId}</code>
            </h2>
            <p class="drawer__sub muted">
              {timestamp(entry.state.nowMillis)} · {PLACEMENT_LABELS[entry.request.placement]} · “{entry.request.query}”
            </p>
          </div>
          <button type="button" class="icon-button" ref={closeRef} onClick={onClose} aria-label="Close explanation">
            <IconX size={18} />
          </button>
        </header>

        <div class="drawer__body">
          <section class="explain-section" aria-labelledby="explain-candidates">
            <h3 class="visually-hidden" id="explain-candidates">
              Decisions
            </h3>
            <div class="tabs" role="tablist" aria-label="Candidates">
              {traced.traces.map((candidateTrace, index) => {
                const sample = sampleOf(candidateTrace.candidate.campaignId);
                return (
                  <button
                    type="button"
                    role="tab"
                    key={candidateTrace.candidate.adId + index}
                    id={`explain-tab-${index}`}
                    aria-selected={index === selected}
                    aria-controls="explain-rules"
                    tabIndex={index === selected ? 0 : -1}
                    class={`tab tab--${toneOf(candidateTrace.decision.reason)}`}
                    onClick={() => setSelected(index)}
                    onKeyDown={(event) => {
                      const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
                      if (step !== 0) {
                        event.preventDefault();
                        const next = (index + step + traced.traces.length) % traced.traces.length;
                        setSelected(next);
                        document.getElementById(`explain-tab-${next}`)?.focus();
                      }
                    }}
                  >
                    <CampaignMark name={sample?.name ?? candidateTrace.candidate.campaignId} color={sample?.color ?? '#78716c'} size={20} />
                    <span>{sample?.name ?? candidateTrace.candidate.campaignId}</span>
                    {candidateTrace.decision.eligible ? <IconCheck size={14} /> : <IconX size={14} />}
                  </button>
                );
              })}
            </div>

            <div class="verdict-box" id="explain-rules" role="tabpanel" aria-labelledby={`explain-tab-${selected}`}>
              <div class="verdict-box__line">
                <VerdictBadge reason={trace.decision.reason} />
                <ReasonChip reason={trace.decision.reason} />
              </div>
              <p class="verdict-box__plain">{REASON_PLAIN[trace.decision.reason]}</p>
              <p class="verdict-box__detail">
                <span class="muted">Engine detail:</span> <code>{String(trace.decision.detail)}</code>
              </p>
              <h4 class="rules__title">Rule by rule, in the engine's order</h4>
              <ol class="rules">
                {CHECKS.map((check, index) => {
                  const result: CheckResult | undefined = trace.checks.find((item) => item.id === check.id);
                  const outcome = result?.outcome ?? 'not-reached';
                  return (
                    <li key={check.id} class={`rule rule--${outcome}`}>
                      <span class="rule__icon" aria-hidden="true">
                        {outcome === 'pass' ? (
                          <IconCheck size={13} />
                        ) : outcome === 'fail' ? (
                          <IconX size={13} />
                        ) : outcome === 'skip' ? (
                          <span>–</span>
                        ) : (
                          <span>{index + 1}</span>
                        )}
                      </span>
                      <span class="rule__body">
                        <span class="rule__title">
                          {check.label}
                          <span class="visually-hidden">: {outcome === 'not-reached' ? 'not evaluated' : outcome}</span>
                          {outcome === 'fail' ? <code class="rule__code">{check.reason}</code> : null}
                        </span>
                        <span class="rule__note">
                          {result
                            ? result.note
                            : outcome === 'not-reached'
                              ? 'Not evaluated: the engine stops at the first failing check.'
                              : ''}
                        </span>
                      </span>
                    </li>
                  );
                })}
              </ol>
            </div>
          </section>

          <section class="explain-section" aria-labelledby="explain-request">
            <h3 class="explain-section__title" id="explain-request">
              The request
            </h3>
            <dl class="facts">
              <div>
                <dt>Request id</dt>
                <dd>
                  <code>{entry.request.requestId}</code>
                </dd>
              </div>
              <div>
                <dt>Placement</dt>
                <dd>
                  <code>{entry.request.placement}</code>
                </dd>
              </div>
              <div>
                <dt>Search query</dt>
                <dd>
                  {entry.request.query ? `“${entry.request.query}”` : <span class="muted">none</span>}
                  {queryTokens.length > 0 ? (
                    <span class="muted">
                      {' '}
                      · normalized to{' '}
                      {queryTokens.map((token, index) => (
                        <code class="token" key={`${token}-${index}`}>
                          {token}
                        </code>
                      ))}
                    </span>
                  ) : null}
                </dd>
              </div>
              <div>
                <dt>Device</dt>
                <dd>
                  <code>{entry.request.device.id}</code>
                </dd>
              </div>
              <div>
                <dt>User agent</dt>
                <dd>
                  <code>{entry.request.device.userAgent ?? 'none'}</code>
                </dd>
              </div>
              <div>
                <dt>Candidates</dt>
                <dd>
                  {entry.request.candidates.map((candidate, index) => (
                    <span key={`${candidate.adId}-${index}`} class="candidate">
                      <code>{candidate.adId}</code>
                      <span class="muted"> → </span>
                      <code>{candidate.campaignId}</code>
                    </span>
                  ))}
                </dd>
              </div>
            </dl>
          </section>

          <section class="explain-section" aria-labelledby="explain-state">
            <h3 class="explain-section__title" id="explain-state">
              State snapshot the engine saw
            </h3>
            <dl class="facts">
              <div>
                <dt>Decision time</dt>
                <dd>
                  {timestamp(entry.state.nowMillis)} <span class="muted">· nowMillis {entry.state.nowMillis}</span>
                </dd>
              </div>
              <div>
                <dt>Serving state</dt>
                <dd>
                  <code>{entry.state.dependencyStatus}</code>
                  {entry.state.dependencyDetail ? <span class="muted"> · {entry.state.dependencyDetail}</span> : null}
                </dd>
              </div>
              <div>
                <dt>Device counter</dt>
                <dd>
                  {entry.state.deviceRequestsInWindow} {entry.state.deviceRequestsInWindow === 1 ? 'request' : 'requests'} in this{' '}
                  {DEFAULT_SETTINGS.deviceRateWindowMillis / 1000} s window
                  <span class="muted"> (limit {DEFAULT_SETTINGS.deviceRequestLimit})</span>
                </dd>
              </div>
              <div>
                <dt>State read took</dt>
                <dd>{entry.loadMillis.toFixed(2)} ms (simulated)</dd>
              </div>
            </dl>
            <div class="table-wrap">
              <table class="snapshot">
                <caption class="visually-hidden">Per-campaign state in the snapshot</caption>
                <thead>
                  <tr>
                    <th scope="col">Campaign</th>
                    <th scope="col">Config</th>
                    <th scope="col">Spent today</th>
                    <th scope="col">Spend lag</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.values(entry.state.campaigns).map((campaignState) => (
                    <tr key={campaignState.campaignId}>
                      <th scope="row">
                        <code>{campaignState.campaignId}</code>
                      </th>
                      <td>
                        {campaignState.configStatus}
                        <span class="muted"> · {duration(Math.max(0, entry.state.nowMillis - campaignState.configLoadedAtMillis))} old</span>
                      </td>
                      <td>
                        {money(campaignState.spentTodayMicros)}
                        {campaignState.campaign ? <span class="muted"> of {money(campaignState.campaign.dailyBudgetMicros)}</span> : null}
                      </td>
                      <td>
                        {campaignState.spendWatermarkMillis === null
                          ? 'unknown'
                          : duration(Math.max(0, entry.state.nowMillis - campaignState.spendWatermarkMillis))}
                        <span class="muted"> · p{campaignState.spendPartition}</span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <details class="raw">
              <summary>Raw request, snapshot and decisions (JSON)</summary>
              <pre>
                <code>{JSON.stringify({ request: entry.request, state: entry.state, decisions: entry.decisions }, null, 2)}</code>
              </pre>
            </details>
          </section>

          <section class="explain-section" aria-labelledby="explain-replay">
            <h3 class="explain-section__title" id="explain-replay">
              Replay
            </h3>
            <p class="muted small">
              The engine is a pure function of (request, snapshot), so the stored snapshot must reproduce the decision exactly.
            </p>
            <div class="button-row">
              <button type="button" class="button button--small" onClick={replaySnapshot}>
                <IconReplay size={14} />
                Replay on the stored snapshot
              </button>
              <button type="button" class="button button--small button--ghost" onClick={replayCurrent}>
                Evaluate against current state
              </button>
            </div>
            {replay ? (
              <div class={`replay ${replay.identical ? 'replay--same' : 'replay--diff'}`} role="status">
                <p>
                  {replay.kind === 'snapshot'
                    ? replay.identical
                      ? 'Reproducible: replaying the stored snapshot gave identical decisions.'
                      : 'Not reproducible: the replay differs. This should never happen.'
                    : replay.identical
                      ? 'Against the current state every candidate would get the same reason:'
                      : 'Against the current state the reasons would differ:'}
                </p>
                <ul class="replay__list">
                  {replay.decisions.map((decision, index) => (
                    <li key={decision.adId + index}>
                      <span>{sampleOf(decision.campaignId)?.name ?? decision.campaignId}</span>
                      <ReasonChip reason={decision.reason} />
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </section>
          <p class="muted small drawer__foot">
            <IconLock size={12} /> Fail-closed codes mean “could not prove it is safe to serve”, as opposed to a policy decline.
          </p>
        </div>
      </div>
    </div>
  );
}

function trapFocus(event: KeyboardEvent, container: HTMLElement | null): void {
  if (!container) {
    return;
  }
  const focusable = [
    ...container.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input, select, summary, [tabindex]:not([tabindex="-1"])'),
  ].filter((element) => element.offsetParent !== null);
  if (focusable.length === 0) {
    return;
  }
  const first = focusable[0]!;
  const last = focusable[focusable.length - 1]!;
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}
