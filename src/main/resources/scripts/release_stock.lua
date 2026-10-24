-- KEYS[1]: stock:{sku}
-- KEYS[2]: reservation:{reservationId}
-- ARGV[1]: fallback quantity from persistent ledger (if Redis TTL already expired)

local payload = redis.call('GET', KEYS[2])
local refund_qty = tonumber(ARGV[1]) or 0

if payload ~= false and payload ~= nil then
    local ok, decoded = pcall(cjson.decode, payload)
    if ok and decoded and decoded['quantity'] ~= nil then
        refund_qty = tonumber(decoded['quantity'])
    end
    redis.call('DEL', KEYS[2])
end

if refund_qty > 0 then
    redis.call('INCRBY', KEYS[1], refund_qty)
    return refund_qty
end

return 0
