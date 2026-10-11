local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local data = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(data[1])
local lastRefill = tonumber(data[2])

if tokens == nil or lastRefill == nil then
  tokens = capacity
  lastRefill = now
end

local elapsed = now - lastRefill
if elapsed < 0 then
  elapsed = 0
end
tokens = math.min(capacity, tokens + elapsed * refillPerSecond / 1000)

local allowed = 0
local retryMs = 0

if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retryMs = math.ceil((cost - tokens) * 1000 / refillPerSecond)
  if retryMs < 1 then
    retryMs = 1
  end
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', now)
redis.call('PEXPIRE', key, math.ceil(capacity / refillPerSecond * 1000) * 2 + 1000)

local resetMs = math.ceil((capacity - tokens) * 1000 / refillPerSecond)

return {allowed, math.floor(tokens), resetMs, retryMs}
