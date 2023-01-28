package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author kaede
 * @create 2023-01-24
 */

@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 将Java对象序列化为Json，存储至Redis中
    public void set(String key, Object value) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    // 将Java对象序列化为Json，存储至Redis中，并设置过期时间
    public void set(String key, Object value, Long expireTime, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expireTime, unit);
    }

    // 将Java对象序列化为Json，存储至Redis中，并设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicalExpire(String key, Object value, Long expireTime, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
        // 写入Redis
        this.set(key, redisData);
    }

    // 根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                        Long expireTime, TimeUnit unit, Long nullTime, TimeUnit nullUnit) {
        // 1.从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(key)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        } else if (json != null) {
            // 3.命中空值
            return null;
        }
        // 4.不存在，查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回空值
        if (r == null) {
            // 将空值写入redis，设置过期时间为2min
            this.set(key, "", nullTime, nullUnit);
            return null;
        }
        // 6.存在，将数据写入redis，并设置过期时间30min
        this.set(key, r, expireTime, unit);
        // 7.返回数据
        return r;
    }

    private boolean tryLock(String key, Long lockTime, TimeUnit lockUnit) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", lockTime, lockUnit);
        // 直接返回可能会因为自动拆箱出现空指针问题
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    // 根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, String lockKeyPrefix,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit,
                                            Long lockTime, TimeUnit lockUnit) {
        // 1.从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.不存在，直接返回
            return null;
        }
        // 4.存在，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，返回店铺信息
            return r;
        }
        // 5.2.已过期，需要重建缓存
        // 6.1.获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey, lockTime, lockUnit);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // double check
            redisData = JSONUtil.toBean(json, RedisData.class);
            r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            // 6.3.获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期信息
        return r;
    }

}
