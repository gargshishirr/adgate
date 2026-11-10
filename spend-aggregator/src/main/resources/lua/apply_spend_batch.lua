-- Applies a batch of spend events atomically and idempotently.
-- KEYS: one (dedupeKey, spendKey) pair per event
-- ARGV[1]: dedupe TTL in seconds, ARGV[2]: spend counter TTL in seconds, ARGV[3..]: cost in micros per event
local dedupeTtl = tonumber(ARGV[1])
local spendTtl = tonumber(ARGV[2])
local applied = 0
for i = 1, #KEYS / 2 do
  local dedupeKey = KEYS[2 * i - 1]
  local spendKey = KEYS[2 * i]
  if redis.call('SET', dedupeKey, '1', 'NX', 'EX', dedupeTtl) then
    redis.call('INCRBY', spendKey, ARGV[i + 2])
    redis.call('EXPIRE', spendKey, spendTtl)
    applied = applied + 1
  end
end
return applied
