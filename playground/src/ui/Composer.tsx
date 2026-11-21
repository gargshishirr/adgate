import { useState } from 'preact/hooks';

import { PLACEMENTS, type Placement } from '../engine/types';
import { RETIRED_CAMPAIGN_ID } from '../sim/campaigns';
import { DEVICE_PRESETS, type LoggedDecision, type Simulation } from '../sim/simulation';
import { CampaignMark, Panel, ReasonChip, ToggleChip, VerdictBadge } from './common';
import { clockSeconds, PLACEMENT_LABELS, REASON_PLAIN } from './format';
import { IconSend } from './icons';

const SUGGESTIONS = ['photo editor', 'free photo editor', 'home workout', 'cheap flights', 'weekend getaway'];

export function ComposerPanel({ sim, onExplain }: { sim: Simulation; onExplain: (requestId: string, adId?: string) => void }) {
  const [placement, setPlacement] = useState<Placement>('SEARCH_RESULTS');
  const [query, setQuery] = useState('photo editor');
  const [deviceId, setDeviceId] = useState(DEVICE_PRESETS[0]!.id);
  const [campaignIds, setCampaignIds] = useState<string[]>(sim.samples.map((sample) => sample.campaign.id));
  const [result, setResult] = useState<{ entry: LoggedDecision; burst: number } | null>(null);
  const preset = DEVICE_PRESETS.find((device) => device.id === deviceId)!;

  const toggleCampaign = (id: string) =>
    setCampaignIds(campaignIds.includes(id) ? campaignIds.filter((c) => c !== id) : [...campaignIds, id]);

  const send = (event: Event) => {
    event.preventDefault();
    if (campaignIds.length > 0) {
      setResult({ entry: sim.send({ placement, query, deviceId, campaignIds }), burst: preset.burst });
    }
  };

  const nameOf = (id: string) => sim.samples.find((sample) => sample.campaign.id === id);

  return (
    <Panel id="composer" title="Send a request" kicker="Be the ad server">
      <form class="composer" onSubmit={send}>
        <fieldset class="field">
          <legend class="field__label">Placement</legend>
          <div class="chips">
            {PLACEMENTS.map((value) => (
              <button
                type="button"
                key={value}
                class="chip chip--choice"
                aria-pressed={placement === value}
                onClick={() => setPlacement(value)}
              >
                {PLACEMENT_LABELS[value]}
              </button>
            ))}
          </div>
        </fieldset>

        <div class="field">
          <label class="field__label" for="query">
            Search query
          </label>
          <input
            id="query"
            type="text"
            value={query}
            maxLength={512}
            autocomplete="off"
            spellcheck={false}
            onInput={(event) => setQuery(event.currentTarget.value)}
          />
          <div class="suggestions" role="group" aria-label="Example queries">
            {SUGGESTIONS.map((suggestion) => (
              <button type="button" key={suggestion} class="link-chip" onClick={() => setQuery(suggestion)}>
                {suggestion}
              </button>
            ))}
          </div>
        </div>

        <div class="field">
          <label class="field__label" for="device">
            Device
          </label>
          <select id="device" value={deviceId} onChange={(event) => setDeviceId(event.currentTarget.value)}>
            {DEVICE_PRESETS.map((device) => (
              <option key={device.id} value={device.id}>
                {device.label}
              </option>
            ))}
          </select>
          <p class="field__hint">
            User agent: <code>{preset.userAgent}</code>
          </p>
        </div>

        <fieldset class="field">
          <legend class="field__label">Candidate ads from retrieval</legend>
          <div class="chips">
            {sim.samples.map((sample) => (
              <ToggleChip
                key={sample.campaign.id}
                pressed={campaignIds.includes(sample.campaign.id)}
                onToggle={() => toggleCampaign(sample.campaign.id)}
              >
                {sample.name}
              </ToggleChip>
            ))}
            <ToggleChip
              pressed={campaignIds.includes(RETIRED_CAMPAIGN_ID)}
              onToggle={() => toggleCampaign(RETIRED_CAMPAIGN_ID)}
              title="A campaign that has been deleted"
            >
              {RETIRED_CAMPAIGN_ID} (deleted)
            </ToggleChip>
          </div>
        </fieldset>

        <button type="submit" id="send-button" class="button button--primary button--large" disabled={campaignIds.length === 0}>
          <IconSend size={18} />
          {preset.burst > 1 ? `Send ${preset.burst} ad requests` : 'Send ad request'}
        </button>
      </form>

      <div class="results" aria-live="polite">
        {result ? (
          <>
            <p class="results__head">
              <code>{result.entry.request.requestId}</code> at {clockSeconds(result.entry.state.nowMillis)} UTC
              {result.burst > 1 ? (
                <>
                  {' '}
                  · the last of {result.burst} requests from <code>{result.entry.request.device.id}</code>, which made{' '}
                  {result.entry.state.deviceRequestsInWindow} in this 10 s window
                </>
              ) : null}
            </p>
            <ul class="results__list">
              {result.entry.decisions.map((decision) => {
                const sample = nameOf(decision.campaignId);
                return (
                  <li key={decision.adId} class="result">
                    <CampaignMark name={sample?.name ?? decision.campaignId} color={sample?.color ?? '#78716c'} size={28} />
                    <div class="result__body">
                      <div class="result__line">
                        <span class="result__name">{sample?.name ?? decision.campaignId}</span>
                        <VerdictBadge reason={decision.reason} />
                      </div>
                      <p class="result__plain">
                        <ReasonChip reason={decision.reason} /> {REASON_PLAIN[decision.reason]}
                      </p>
                      <p class="result__detail">
                        <code>{decision.detail}</code>{' '}
                        <button
                          type="button"
                          class="link-chip"
                          onClick={() => onExplain(result.entry.request.requestId, decision.adId)}
                          aria-label={`Explain the decision for ${sample?.name ?? decision.campaignId}`}
                        >
                          Explain
                        </button>
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>
          </>
        ) : (
          <p class="results__empty muted">Decisions for each candidate will appear here, with the reason and the exact rule that decided it.</p>
        )}
      </div>
    </Panel>
  );
}
