/**
 * Port of SpendPartitioner.java (adgate-common): the partition of the spend topic that owns a campaign, using
 * Kafka's default partitioner for keyed records, toPositive(murmur2(utf8(key))) % numPartitions. The eligibility
 * service checks that partition's watermark, so a stalled partition fails closed only the campaigns it carries.
 */

const encoder = new TextEncoder();

/** org.apache.kafka.common.utils.Utils.murmur2. */
export function murmur2(data: Uint8Array): number {
  const length = data.length;
  const seed = 0x9747b28c | 0;
  const m = 0x5bd1e995;
  const r = 24;

  let h = seed ^ length;
  const length4 = Math.floor(length / 4);
  for (let i = 0; i < length4; i++) {
    const i4 = i * 4;
    let k = (data[i4]! & 0xff) + ((data[i4 + 1]! & 0xff) << 8) + ((data[i4 + 2]! & 0xff) << 16) + ((data[i4 + 3]! & 0xff) << 24);
    k |= 0;
    k = Math.imul(k, m);
    k ^= k >>> r;
    k = Math.imul(k, m);
    h = Math.imul(h, m);
    h ^= k;
  }

  const tail = length & ~3;
  switch (length % 4) {
    case 3:
      h ^= (data[tail + 2]! & 0xff) << 16;
    // falls through
    case 2:
      h ^= (data[tail + 1]! & 0xff) << 8;
    // falls through
    case 1:
      h ^= data[tail]! & 0xff;
      h = Math.imul(h, m);
  }

  h ^= h >>> 13;
  h = Math.imul(h, m);
  h ^= h >>> 15;
  return h | 0;
}

export function partitionFor(campaignId: string, numPartitions: number): number {
  if (numPartitions <= 0) {
    throw new Error('numPartitions must be positive');
  }
  return (murmur2(encoder.encode(campaignId)) & 0x7fffffff) % numPartitions;
}
