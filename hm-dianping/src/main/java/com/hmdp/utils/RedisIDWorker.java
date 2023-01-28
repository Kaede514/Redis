package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static com.hmdp.utils.RedisConstants.INCR_ID_KEY;

/**
 * @author kaede
 * @create 2023-01-24
 */

@Component
public class RedisIDWorker {

    // 开始时间戳
    public static final long BEGIN_TIMESTAMP;
    // 序列号位数
    public static final int COUNT_BITS = 32;

    static  {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        BEGIN_TIMESTAMP = time.toEpochSecond(ZoneOffset.UTC);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSeconds = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSeconds - BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1.获取当前日期，精确到天（方便统计）
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长，"incr:" + keyPrefix + ":" + date，没有key则会直接生成key且值为0
        long count = stringRedisTemplate.opsForValue().increment(INCR_ID_KEY + keyPrefix + ":" + date);
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
