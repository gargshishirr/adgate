-- Moves partition watermarks forward, never backward. During a consumer-group rebalance the old and new
-- owner of a partition can both report; keeping the maximum keeps the guarantee "everything up to the
-- watermark has been applied" true regardless of report order.
-- KEYS: watermark keys, ARGV: candidate watermark (epoch millis) for each key
for i = 1, #KEYS do
  local candidate = tonumber(ARGV[i])
  local current = tonumber(redis.call('GET', KEYS[i]) or '0')
  if candidate > current then
    redis.call('SET', KEYS[i], ARGV[i])
  end
end
return #KEYS
