-- 获取锁中的线程标识
local id = redis.call('get', KEYS[1])
-- 比较线程标识与锁中的标识是否一致
if (ARGV[1] == id) then
    -- 释放锁
    return redis.call('del', id)
end
return 0