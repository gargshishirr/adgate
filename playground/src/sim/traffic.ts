import type { EligibilityRequest, Placement } from '../engine/types';
import type { Rng } from './rng';

/**
 * Synthetic ad requests, modelled on traffic-sim/TrafficSimulator.java: a weighted placement mix, a fixed query
 * list, a share of automated traffic (a bot user agent or one hyperactive device), and now and then a candidate
 * whose campaign no longer exists.
 */

export const QUERIES: readonly string[] = [
  'home workout',
  'fitness tracker',
  'yoga for beginners',
  'photo editor',
  'free photo editor',
  'photo collage',
  'weekend getaway',
  'travel deals',
  'cheap flights',
  'recipe app',
  'meal planner',
  'free games',
  'budget planner',
  'Cheap workout gear',
];

export const PLACEMENT_MIX: readonly Placement[] = [
  'SEARCH_RESULTS',
  'SEARCH_RESULTS',
  'SEARCH_RESULTS',
  'SEARCH_TAB',
  'TODAY_TAB',
  'PRODUCT_PAGES',
];

export const HUMAN_USER_AGENT = 'AppStore/3.0 iOS/19.1';
export const BOT_USER_AGENT = 'Mozilla/5.0 (compatible; ExampleBot/2.1)';
export const HOT_DEVICE_ID = 'device-hot';

export interface TrafficMix {
  readonly devices: number;
  readonly candidatesPerRequest: number;
  /** Share of requests from automated traffic: half from a bot user agent, half from one hyperactive device. */
  readonly botShare: number;
  readonly unknownCampaignShare: number;
}

/** traffic-sim's defaults. */
export const DEFAULT_MIX: TrafficMix = {
  devices: 500,
  candidatesPerRequest: 3,
  botShare: 0.04,
  unknownCampaignShare: 0.02,
};

export function generateRequest(
  rng: Rng,
  campaignIds: readonly string[],
  unknownCampaignId: string,
  mix: TrafficMix = DEFAULT_MIX,
): EligibilityRequest {
  let deviceId = `device-${rng.int(mix.devices)}`;
  let userAgent = HUMAN_USER_AGENT;
  if (rng.chance(mix.botShare)) {
    if (rng.chance(0.5)) {
      deviceId = HOT_DEVICE_ID;
    } else {
      userAgent = BOT_USER_AGENT;
    }
  }
  const chosen = rng.shuffle(campaignIds).slice(0, mix.candidatesPerRequest);
  if (rng.chance(mix.unknownCampaignShare)) {
    chosen.push(unknownCampaignId);
  }
  return {
    requestId: `req-${rng.token(8)}`,
    placement: rng.pick(PLACEMENT_MIX),
    query: rng.pick(QUERIES),
    device: { id: deviceId, userAgent },
    candidates: chosen.map((campaignId) => ({ adId: `ad-${campaignId}`, campaignId })),
  };
}
