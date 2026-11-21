import { DEFAULT_SETTINGS, LATENCY_BUDGET_MILLIS } from '../engine/settings';
import type { ReasonCode } from '../engine/types';
import type { Simulation } from '../sim/simulation';
import { SPEND_PARTITIONS, STALLED_PARTITION, type ChaosSwitch } from '../sim/world';
import { partitionFor } from '../engine/partitioner';
import { Panel, ReasonChip, Switch } from './common';
import { duration } from './format';

interface SwitchSpec {
  readonly key: ChaosSwitch;
  readonly title: string;
  readonly what: string;
  readonly expect: ReasonCode;
  readonly when: string;
}

export function ChaosPanel({ sim }: { sim: Simulation }) {
  const chaos = sim.world.chaos;
  const anyOn = Object.values(chaos).some(Boolean);
  const breaker = sim.world.breakerState();
  const lags = Array.from({ length: SPEND_PARTITIONS }, (_, partition) => sim.world.spendLagMillis(partition));
  const freshestLag = Math.min(...lags);
  const configAges = sim.samples.map((sample) => sim.world.configAgeMillis(sample.campaign.id) ?? 0);
  const maxConfigAge = Math.max(...configAges);
  const stalledCampaigns = sim.samples
    .filter((sample) => partitionFor(sample.campaign.id, SPEND_PARTITIONS) === STALLED_PARTITION)
    .map((sample) => sample.name);
  const lastLoad = sim.log[0]?.loadMillis ?? 0;

  const switches: SwitchSpec[] = [
    {
      key: 'redisDown',
      title: 'Redis down',
      what: 'Every Redis call fails.',
      expect: 'DEPENDENCY_UNAVAILABLE',
      when: 'at once; after 20 failures the circuit breaker opens and Redis is not called at all',
    },
    {
      key: 'slowRedis',
      title: 'Slow Redis',
      what: 'Calls take 33–39 ms, plus a wait for a pooled connection; slow calls also trip the breaker.',
      expect: 'DEADLINE_EXCEEDED',
      when: `when a read passes the ${LATENCY_BUDGET_MILLIS} ms budget`,
    },
    {
      key: 'spendPipelineStalled',
      title: 'Spend pipeline stalled',
      what: 'The aggregator stops; every watermark freezes.',
      expect: 'SPEND_DATA_STALE',
      when: `after ${DEFAULT_SETTINGS.maxSpendStalenessMillis / 1000} s`,
    },
    {
      key: 'partitionStalled',
      title: `Kafka partition ${STALLED_PARTITION} stalled`,
      what: `Only partition ${STALLED_PARTITION} freezes; it carries ${stalledCampaigns.join(', ') || 'no sample campaign'}.`,
      expect: 'SPEND_DATA_STALE',
      when: `for ${stalledCampaigns.join(', ') || 'those campaigns'} alone`,
    },
    {
      key: 'configRefreshFailing',
      title: 'Config refresh failing',
      what: 'Cached campaign configs keep aging.',
      expect: 'CONFIG_STALE',
      when: `after ${DEFAULT_SETTINGS.maxConfigAgeMillis / 1000} s`,
    },
  ];

  const gauges: Record<ChaosSwitch, { label: string; value: string; ratio: number; bad: boolean }> = {
    redisDown: {
      label: 'Circuit breaker',
      value: breaker.replace('_', '-').toLowerCase(),
      ratio: breaker === 'CLOSED' ? 0 : breaker === 'HALF_OPEN' ? 0.5 : 1,
      bad: breaker !== 'CLOSED' || chaos.redisDown,
    },
    slowRedis: {
      label: 'Last state read',
      value: `${lastLoad.toFixed(1)} ms of ${LATENCY_BUDGET_MILLIS} ms`,
      ratio: lastLoad / LATENCY_BUDGET_MILLIS,
      bad: lastLoad > LATENCY_BUDGET_MILLIS,
    },
    spendPipelineStalled: {
      label: 'Every partition',
      value: `≥ ${duration(freshestLag)} behind`,
      ratio: freshestLag / DEFAULT_SETTINGS.maxSpendStalenessMillis,
      bad: freshestLag > DEFAULT_SETTINGS.maxSpendStalenessMillis,
    },
    partitionStalled: {
      label: `Partition ${STALLED_PARTITION}`,
      value: `${duration(lags[STALLED_PARTITION]!)} behind`,
      ratio: lags[STALLED_PARTITION]! / DEFAULT_SETTINGS.maxSpendStalenessMillis,
      bad: lags[STALLED_PARTITION]! > DEFAULT_SETTINGS.maxSpendStalenessMillis,
    },
    configRefreshFailing: {
      label: 'Oldest cached config',
      value: duration(maxConfigAge),
      ratio: maxConfigAge / DEFAULT_SETTINGS.maxConfigAgeMillis,
      bad: maxConfigAge > DEFAULT_SETTINGS.maxConfigAgeMillis,
    },
  };

  return (
    <Panel
      id="chaos"
      title="Chaos switches"
      kicker="Break things safely"
      actions={
        <button type="button" class="button button--small button--ghost" onClick={() => sim.recoverAll()} disabled={!anyOn}>
          Recover all
        </button>
      }
    >
      <p class="panel__intro">
        Switches change the engine's inputs, never the engine. Missing or stale inputs must become a decline with a
        reason.
      </p>
      <ul class="chaos">
        {switches.map((spec) => {
          const gauge = gauges[spec.key];
          const on = chaos[spec.key];
          const descriptionId = `chaos-${spec.key}-desc`;
          return (
            <li key={spec.key} class={`chaos__item ${on ? 'chaos__item--on' : ''}`}>
              <div class="chaos__head">
                <label class="chaos__title" for={`chaos-${spec.key}`}>
                  {spec.title}
                </label>
                <Switch
                  id={`chaos-${spec.key}`}
                  checked={on}
                  tone="danger"
                  label={spec.title}
                  describedBy={descriptionId}
                  onChange={(checked) => sim.setChaos(spec.key, checked)}
                />
              </div>
              <p class="chaos__what" id={descriptionId}>
                {spec.what} Expect <ReasonChip reason={spec.expect} /> {spec.when}.
              </p>
              <div class="gauge">
                <span class="gauge__label">{gauge.label}</span>
                <span class="gauge__track" aria-hidden="true">
                  <span class={`gauge__fill ${gauge.bad ? 'gauge__fill--bad' : ''}`} style={{ width: `${Math.min(1, gauge.ratio) * 100}%` }} />
                </span>
                <span class={`gauge__value ${gauge.bad ? 'gauge__value--bad' : ''}`}>{gauge.value}</span>
              </div>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
