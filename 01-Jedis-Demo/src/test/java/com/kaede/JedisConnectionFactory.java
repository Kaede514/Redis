package com.kaede;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * @author kaede
 * @create 2023-01-22
 */

public class JedisConnectionFactory {

    public static final JedisPool jedisPool;

    static {
        // 配置连接池
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        // 1.最大连接数
        poolConfig.setMaxTotal(8);
        // 2.最大空闲连接
        poolConfig.setMaxIdle(8);
        // 3.最小空闲连接
        poolConfig.setMinIdle(0);
        // 4.等待时长(连接池中无连接时是否等待、等待时长，默认为-1，即无限制等待)
        poolConfig.setMaxWaitMillis(1000);
        // 创建连接池对象
        // 配置对象 IP 端口 超时时间 密码
        jedisPool = new JedisPool(poolConfig,
            "192.168.138.128", 6379, 1000);
    }

    public static Jedis getJedis() {
        return jedisPool.getResource();
    }

}
