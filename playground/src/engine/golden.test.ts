import { describe, expect, it } from 'vitest';

import { evaluate, evaluateTraced } from './engine';
import golden from './golden.json';
import { dayFraction, evenPacingAllowanceMicros } from './pacing';
import { partitionFor } from './partitioner';
import { stem, tokens } from './queryNormalizer';
import { unit } from './sampler';
import { DEFAULT_SETTINGS } from './settings';
import type {
  CampaignState,
  Decision,
  EligibilityRequest,
  EngineSettings,
  ServingState,
} from './types';

/**
 * Replays every golden vector produced by the real Java engine (GoldenVectorsTest.java) against the TypeScript
 * port. Decisions, including their detail strings, must match exactly.
 */

interface GoldenEngineVector {
  name: string;
  settings?: EngineSettings;
  request: EligibilityRequest;
  state: Omit<ServingState, 'campaigns'> & { campaigns: CampaignState[] };
  decisions: Decision[];
}

interface Golden {
  engine: GoldenEngineVector[];
  sampler: { requestId: string; campaignId: string; unit: number }[];
  tokens: { text: string | null; tokens: string[] }[];
  stems: { token: string; stem: string }[];
  pacing: { epochMillis: number; dayFraction: number; dailyBudgetMicros: number; allowanceMicros: number }[];
  partitions: { campaignId: string; partitions: number; partition: number }[];
}

const vectors = golden as unknown as Golden;

function servingState(vector: GoldenEngineVector): ServingState {
  const campaigns: Record<string, CampaignState> = {};
  for (const campaign of vector.state.campaigns) {
    campaigns[campaign.campaignId] = campaign;
  }
  return { ...vector.state, campaigns };
}

describe('golden vectors from the Java engine', () => {
  it('covers every reason code and a broad set of inputs', () => {
    const reasons = new Set(vectors.engine.flatMap((vector) => vector.decisions.map((decision) => decision.reason)));
    expect(reasons.size).toBe(13);
    expect(vectors.engine.length).toBeGreaterThan(500);
  });

  it.each(vectors.engine.map((vector, index) => [index, vector.name, vector] as const))(
    'engine #%i: %s',
    (_index, _name, vector) => {
      const decisions = evaluate(vector.request, servingState(vector), vector.settings ?? DEFAULT_SETTINGS);
      expect(decisions).toEqual(vector.decisions);
    },
  );

  it('the traced evaluation reaches the same decisions, and its last check names the reason', () => {
    for (const vector of vectors.engine) {
      const traced = evaluateTraced(vector.request, servingState(vector), vector.settings ?? DEFAULT_SETTINGS);
      expect(traced.decisions).toEqual(vector.decisions);
      traced.traces.forEach((trace, index) => {
        const decision = vector.decisions[index]!;
        const failed = trace.checks.filter((check) => check.outcome === 'fail');
        if (decision.eligible) {
          expect(failed).toEqual([]);
        } else {
          expect(failed).toHaveLength(1);
          expect(trace.checks.at(-1)?.outcome).toBe('fail');
        }
      });
    }
  });

  it('sampler draws are bit-for-bit identical', () => {
    for (const vector of vectors.sampler) {
      expect(unit(vector.requestId, vector.campaignId)).toBe(vector.unit);
    }
  });

  it('query normalization produces the same tokens', () => {
    for (const vector of vectors.tokens) {
      expect(tokens(vector.text)).toEqual(vector.tokens);
    }
    for (const vector of vectors.stems) {
      expect(stem(vector.token)).toBe(vector.stem);
    }
  });

  it('pacing math is identical', () => {
    for (const vector of vectors.pacing) {
      const fraction = dayFraction(vector.epochMillis);
      expect(fraction).toBe(vector.dayFraction);
      expect(evenPacingAllowanceMicros(vector.dailyBudgetMicros, fraction, 0.1, 0.02)).toBe(vector.allowanceMicros);
    }
  });

  it('spend partitions match Kafka murmur2 partitioning', () => {
    for (const vector of vectors.partitions) {
      expect(partitionFor(vector.campaignId, vector.partitions)).toBe(vector.partition);
    }
  });

  it('the build-time vector count matches the file', () => {
    const total = Object.values(golden).reduce((sum, section) => sum + (Array.isArray(section) ? section.length : 0), 0);
    expect(__GOLDEN_VECTOR_COUNT__).toBe(total);
  });
});
