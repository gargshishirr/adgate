import type { Campaign } from '../engine/types';

/** A sample advertiser: the engine's Campaign plus presentation and the simulated market's appetite. */
export interface SampleCampaign {
  readonly campaign: Campaign;
  readonly name: string;
  readonly tagline: string;
  /** Categorical colour for charts; text never relies on it alone. */
  readonly color: string;
  /**
   * Simulated demand: with nothing holding it back, the campaign would win enough auctions to spend its whole
   * daily budget in about this many hours. Even pacing exists to stop exactly that.
   */
  readonly demandHours: number;
}

/** Fictional brands. Ids are chosen to land on different spend partitions (murmur2 % 6: 0, 2, 3 and 5). */
export const SAMPLE_CAMPAIGNS: readonly SampleCampaign[] = [
  {
    campaign: {
      id: 'lumen-fitness',
      status: 'ACTIVE',
      dailyBudgetMicros: 400_000_000,
      pacing: 'EVEN',
      placements: [],
      negativeKeywords: ['free', 'cheap'],
    },
    name: 'Lumen Fitness',
    tagline: 'Home workouts in 15 minutes',
    color: '#4f46e5',
    demandHours: 9,
  },
  {
    campaign: {
      id: 'pixelforge',
      status: 'ACTIVE',
      dailyBudgetMicros: 250_000_000,
      pacing: 'EVEN',
      placements: ['SEARCH_RESULTS', 'SEARCH_TAB'],
      negativeKeywords: ['free photo editor'],
    },
    name: 'PixelForge',
    tagline: 'Pro photo editing on your phone',
    color: '#0e7490',
    demandHours: 6,
  },
  {
    campaign: {
      id: 'trailmix-travel',
      status: 'ACTIVE',
      dailyBudgetMicros: 120_000_000,
      pacing: 'ASAP',
      placements: ['SEARCH_RESULTS', 'TODAY_TAB'],
      negativeKeywords: ['cheap flights'],
    },
    name: 'Trailmix Travel',
    tagline: 'Weekend trips, planned for you',
    color: '#c2410c',
    demandHours: 7,
  },
  {
    campaign: {
      id: 'kitchenly-recipes',
      status: 'ACTIVE',
      dailyBudgetMicros: 180_000_000,
      pacing: 'EVEN',
      placements: [],
      negativeKeywords: [],
    },
    name: 'Kitchenly',
    tagline: 'Recipes from what is in your fridge',
    color: '#15803d',
    // Less demand than even pacing allows: it delivers below the line, because pacing is a cap, not a target.
    demandHours: 32,
  },
];

/** A campaign that was deleted: retrieval still returns it now and then, and the engine says CAMPAIGN_NOT_FOUND. */
export const RETIRED_CAMPAIGN_ID = 'sunset-sale';
