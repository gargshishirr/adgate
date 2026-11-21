import { useState } from 'preact/hooks';

import { PLACEMENTS, type Campaign, type Placement } from '../engine/types';
import type { SampleCampaign } from '../sim/campaigns';
import type { Simulation } from '../sim/simulation';
import { SPEND_PARTITIONS } from '../sim/world';
import { partitionFor } from '../engine/partitioner';
import { CampaignMark, Panel, Segmented, Switch, ToggleChip } from './common';
import { duration, money, PLACEMENT_LABELS, percent, wholeMoney } from './format';
import { IconPencil, IconX } from './icons';

export function CampaignsPanel({ sim }: { sim: Simulation }) {
  return (
    <Panel id="campaigns" title="Campaigns" kicker="Config and spend today">
      <div class="cards">
        {sim.samples.map((sample) => (
          <CampaignCard key={sample.campaign.id} sim={sim} sample={sample} />
        ))}
      </div>
    </Panel>
  );
}

function CampaignCard({ sim, sample }: { sim: Simulation; sample: SampleCampaign }) {
  const [editing, setEditing] = useState(false);
  const campaign = sim.world.campaign(sample.campaign.id) ?? sample.campaign;
  const spent = sim.world.spentToday(campaign.id);
  const inFlight = sim.world.pendingSpend(campaign.id);
  const spentPercent = (100 * spent) / campaign.dailyBudgetMicros;
  const line = sim.pacingLinePercent();
  const even = campaign.pacing === 'EVEN';
  const paused = campaign.status === 'PAUSED';
  const partition = partitionFor(campaign.id, SPEND_PARTITIONS);
  const configAge = sim.world.configAgeMillis(campaign.id);
  const titleId = `card-${campaign.id}`;

  const update = (patch: Partial<Campaign>) => sim.updateCampaign({ ...campaign, ...patch });

  let status: string;
  if (paused) {
    status = 'Paused';
  } else if (spentPercent >= 99.5) {
    status = 'Budget spent';
  } else if (even && spentPercent >= line - 2) {
    status = 'On the pacing line';
  } else {
    status = 'Delivering';
  }

  return (
    <article class={`card ${paused ? 'card--paused' : ''}`} aria-labelledby={titleId}>
      <div class="card__top">
        <CampaignMark name={sample.name} color={sample.color} size={34} />
        <div class="card__identity">
          <h3 class="card__name" id={titleId}>
            {sample.name}
          </h3>
          <p class="card__meta">
            <code>{campaign.id}</code> · partition {partition}
          </p>
        </div>
        <button
          type="button"
          class="button button--small button--ghost card__edit"
          aria-expanded={editing}
          aria-controls={`${titleId}-editor`}
          aria-label={`${editing ? 'Close the editor for' : 'Edit'} ${sample.name}`}
          onClick={() => setEditing(!editing)}
        >
          <IconPencil size={14} />
          {editing ? 'Done' : 'Edit'}
        </button>
      </div>

      <div class="spend">
        <div class="spend__numbers">
          <span>
            <strong>{money(spent)}</strong> <span class="muted">of {wholeMoney(campaign.dailyBudgetMicros)}</span>
          </span>
          <span class="spend__percent">{percent(spentPercent)}</span>
        </div>
        <div
          class="meter"
          role="img"
          aria-label={`Spent ${percent(spentPercent)} of the daily budget${even ? `; the even-pacing line is at ${percent(line)}` : ''}`}
        >
          <div class="meter__fill" style={{ width: `${Math.min(100, spentPercent)}%`, background: sample.color }} />
          {even ? <div class="meter__line" style={{ left: `${Math.min(100, line)}%` }} /> : null}
        </div>
        <div class="spend__caption">
          <span class="muted">
            {even ? (
              <>
                Even pacing · line at <strong>{percent(line)}</strong>
              </>
            ) : (
              <>ASAP · no pacing line</>
            )}
            {inFlight > 0 ? <> · {money(inFlight)} in flight</> : null}
            {configAge !== null && configAge > 5_000 ? <> · config {duration(configAge)} old</> : null}
          </span>
          <span class={`pill ${paused ? 'pill--muted' : status === 'Budget spent' ? 'pill--warn' : 'pill--live'}`}>{status}</span>
        </div>
      </div>

      <dl class="card__facts">
        <div>
          <dt>Placements</dt>
          <dd>{campaign.placements.length === 0 ? 'All' : campaign.placements.map((p) => PLACEMENT_LABELS[p]).join(', ')}</dd>
        </div>
        <div>
          <dt>Negative keywords</dt>
          <dd>
            {campaign.negativeKeywords.length === 0
              ? 'None'
              : campaign.negativeKeywords.map((keyword) => (
                  <span class="kw" key={keyword}>
                    {keyword}
                  </span>
                ))}
          </dd>
        </div>
      </dl>

      {editing ? (
        <CampaignEditor id={`${titleId}-editor`} campaign={campaign} update={update} sim={sim} name={sample.name} />
      ) : null}
    </article>
  );
}

function CampaignEditor(props: {
  id: string;
  campaign: Campaign;
  name: string;
  sim: Simulation;
  update: (patch: Partial<Campaign>) => void;
}) {
  const { campaign, update } = props;
  const [keyword, setKeyword] = useState('');
  const budgetId = `${props.id}-budget`;
  const keywordId = `${props.id}-keyword`;

  const togglePlacement = (placement: Placement) => {
    const has = campaign.placements.includes(placement);
    update({ placements: has ? campaign.placements.filter((p) => p !== placement) : [...campaign.placements, placement] });
  };
  const addKeyword = () => {
    const value = keyword.trim().slice(0, 80);
    if (value && !campaign.negativeKeywords.includes(value)) {
      update({ negativeKeywords: [...campaign.negativeKeywords, value] });
    }
    setKeyword('');
  };

  return (
    <div class="editor" id={props.id}>
      <p class="editor__note">
        Changes apply at once: the config cache entry is invalidated, as the admin API does.
      </p>
      <div class="editor__row">
        <span class="editor__label" id={`${props.id}-status`}>
          Active
        </span>
        <Switch
          checked={campaign.status === 'ACTIVE'}
          label={`${props.name} is active`}
          onChange={(checked) => update({ status: checked ? 'ACTIVE' : 'PAUSED' })}
        />
      </div>
      <div class="editor__row editor__row--stack">
        <label class="editor__label" for={budgetId}>
          Daily budget <strong>{wholeMoney(campaign.dailyBudgetMicros)}</strong>
        </label>
        <input
          id={budgetId}
          type="range"
          min={20}
          max={1000}
          step={10}
          value={campaign.dailyBudgetMicros / 1_000_000}
          onInput={(event) => update({ dailyBudgetMicros: Number(event.currentTarget.value) * 1_000_000 })}
        />
      </div>
      <div class="editor__row">
        <span class="editor__label">Pacing</span>
        <Segmented
          label="Pacing mode"
          size="small"
          value={campaign.pacing}
          options={[
            { value: 'EVEN', label: 'Even' },
            { value: 'ASAP', label: 'ASAP' },
          ]}
          onChange={(pacing) => update({ pacing })}
        />
      </div>
      <fieldset class="editor__row editor__row--stack">
        <legend class="editor__label">Placements (none selected means all)</legend>
        <div class="chips">
          {PLACEMENTS.map((placement) => (
            <ToggleChip key={placement} pressed={campaign.placements.includes(placement)} onToggle={() => togglePlacement(placement)}>
              {PLACEMENT_LABELS[placement]}
            </ToggleChip>
          ))}
        </div>
      </fieldset>
      <div class="editor__row editor__row--stack">
        <label class="editor__label" for={keywordId}>
          Negative keywords
        </label>
        <div class="chips">
          {campaign.negativeKeywords.map((word) => (
            <span class="chip chip--removable" key={word}>
              {word}
              <button
                type="button"
                class="chip__remove"
                aria-label={`Remove negative keyword ${word}`}
                onClick={() => update({ negativeKeywords: campaign.negativeKeywords.filter((k) => k !== word) })}
              >
                <IconX size={12} />
              </button>
            </span>
          ))}
        </div>
        <form
          class="inline-form"
          onSubmit={(event) => {
            event.preventDefault();
            addKeyword();
          }}
        >
          <input
            id={keywordId}
            type="text"
            placeholder="e.g. trial"
            maxLength={80}
            value={keyword}
            onInput={(event) => setKeyword(event.currentTarget.value)}
          />
          <button type="submit" class="button button--small">
            Add
          </button>
        </form>
      </div>
      <div class="editor__row">
        <span class="editor__label">Simulate an auction win</span>
        <button
          type="button"
          class="button button--small"
          onClick={() => props.sim.injectSpend(campaign.id, Math.round(campaign.dailyBudgetMicros * 0.1))}
        >
          + {money(Math.round(campaign.dailyBudgetMicros * 0.1))} spend
        </button>
      </div>
    </div>
  );
}
