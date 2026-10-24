-- KEYS[1]: stock:{sku}
-- KEYS[2]: reservation:{reservationId}
-- ARGV[1]: requested quantity (integer)
-- ARGV[2]: TTL in seconds (e.g. 600)
-- ARGV[3]: reservation JSON payload {"userId":"...","sku":"...","quantity":...}

local current_stock = tonumber(redis.call('GET', KEYS[1]))
local requested_qty = tonumber(ARGV[1])
local ttl_seconds = tonumber(ARGV[2])
local payload = ARGV[3]

if current_stock == nil or current_stock < requested_qty then
    return 0
end

redis.call('DECRBY', KEYS[1], requested_qty)
redis.call('SET', KEYS[2], payload, 'EX', ttl_seconds)
return 1
