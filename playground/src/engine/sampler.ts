/**
 * Port of DeterministicSampler.java: the throttling draw depends only on (request id, campaign id), so a replayed
 * decision makes exactly the same choice. 64-bit arithmetic uses BigInt to match Java's long bit for bit.
 */

const MASK_64 = (1n << 64n) - 1n;

/** String.hashCode(): s[0]*31^(n-1) + ... + s[n-1] over UTF-16 code units, with int overflow. */
export function javaStringHashCode(text: string): number {
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
  }
  return hash;
}

/** SplitMix64 finalizer, on an unsigned 64-bit value. */
function mix(value: bigint): bigint {
  let z = value;
  z = ((z ^ (z >> 30n)) * 0xbf58476d1ce4e5b9n) & MASK_64;
  z = ((z ^ (z >> 27n)) * 0x94d049bb133111ebn) & MASK_64;
  return z ^ (z >> 31n);
}

/** A value in [0, 1) that is uniformly distributed across request ids. */
export function unit(requestId: string, campaignId: string): number {
  // ((long) requestHash << 32) ^ (campaignHash & 0xFFFF_FFFFL), viewed as unsigned.
  const seed = (BigInt(javaStringHashCode(requestId) >>> 0) << 32n) ^ BigInt(javaStringHashCode(campaignId) >>> 0);
  return Number(mix(seed) >> 11n) * 2 ** -53;
}
