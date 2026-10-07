-- 秒杀库存原子扣减脚本
--
-- KEYS[1] = 库存 key        例：campus:seckill:stock:1001
-- KEYS[2] = 已购用户集合 key  例：campus:seckill:users:1001
-- ARGV[1] = userId
--
-- 返回值：
--   0 = 库存不足（售罄）
--   1 = 扣减成功
--   2 = 该用户已购买过（幂等拦截）
--
-- 为什么必须用 Lua：
--   三个操作（幂等检查 → 库存检查 → 扣减并记录）必须是一个原子动作。
--   若拆成三次独立命令，在高并发下多个线程会同时读到同一份 stock，
--   都认为有货并各自 DECR，导致超卖。
--   Redis 执行 Lua 脚本期间是单线程串行的，天然保证原子性。

local stockKey = KEYS[1]
local userKey  = KEYS[2]
local userId   = ARGV[1]

-- 1. 幂等：同一用户不可重复购买
if redis.call('SISMEMBER', userKey, userId) == 1 then
    return 2
end

-- 2. 检查并扣减库存
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil or stock <= 0 then
    return 0
end

redis.call('DECR', stockKey)
redis.call('SADD', userKey, userId)

-- 3. 给已购用户集合设置过期，防止内存无限增长
--    TTL 取活动结束后 1 天足够（活动期间集合需要一直保留）
redis.call('EXPIRE', userKey, 86400)

return 1
