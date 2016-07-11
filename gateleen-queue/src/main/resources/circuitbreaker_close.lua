local stateField = "state"
local failRatioField = "failRatio"

local circuitInfoKey = KEYS[1]
local circuitSuccessKey = KEYS[2]
local circuitFailureKey = KEYS[3]
local circuitQueuesKey = KEYS[4]
local halfOpenCircuitsKey = KEYS[5]
local queuesToUnlockKey = KEYS[6]

local endpointHash = ARGV[1]

-- move queues to 'queues_to_unlock'-queue
local queues = redis.call('zrangebyscore',circuitQueuesKey,'-inf','+inf')
for k, v in ipairs(queues) do
    redis.call('lpush',queuesToUnlockKey,v)
end
redis.call('del',circuitQueuesKey)

-- reset circuit infos
redis.call('hset',circuitInfoKey,stateField,"closed")
redis.call('hset',circuitInfoKey,failRatioField,0)

-- clear success/failure sets
redis.call('del',circuitSuccessKey)
redis.call('del',circuitFailureKey)

-- remove circuit from half-open-circuits set
redis.call('zrem',halfOpenCircuitsKey,endpointHash)

return "OK"