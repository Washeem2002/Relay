local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local index = math.floor(now / window)

local data = redis.call('HMGET', key, 'w', 'c', 'p')
local storedWindow = tonumber(data[1])
local current = tonumber(data[2]) or 0
local previous = tonumber(data[3]) or 0

if storedWindow == nil then
  current = 0
  previous = 0
elseif storedWindow == index - 1 then
  previous = current
  current = 0
elseif storedWindow < index - 1 then
  previous = 0
  current = 0
end

local elapsed = now - index * window
local weight = (window - elapsed) / window
local estimated = previous * weight + current

local allowed = 0
local retryMs = 0

if estimated + cost <= limit then
  current = current + cost
  estimated = estimated + cost
  allowed = 1
else
  if previous > 0 and current + cost <= limit then
    local neededWeight = (limit - cost - current) / previous
    retryMs = math.ceil(window * (1 - neededWeight) - elapsed)
  else
    local untilNextWindow = window - elapsed
    local extra = 0
    if current > 0 and cost <= limit then
      local neededWeight = (limit - cost) / current
      extra = math.ceil(window * (1 - neededWeight))
    elseif cost > limit then
      extra = window
    end
    retryMs = untilNextWindow + extra
  end
  if retryMs < 1 then
    retryMs = 1
  end
end

redis.call('HSET', key, 'w', index, 'c', current, 'p', previous)
redis.call('PEXPIRE', key, window * 2)

local remaining = math.floor(limit - estimated)
if remaining < 0 then
  remaining = 0
end

return {allowed, remaining, window - elapsed, retryMs}
