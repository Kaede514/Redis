package com.kaede;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;

/**
 * @author kaede
 * @create 2023-01-22
 */

public class JedisTest {

    private Jedis jedis;

    @BeforeEach
    void setUp() {
        // 1.建立连接
        // jedis = new Jedis("192.168.138.128", 6379);
        jedis = JedisConnectionFactory.getJedis();
        // 2.设置密码
        jedis.auth("123456");
        // 3.选择库
        jedis.select(0);
    }

    @Test
    public void testString() {
        // 存入数据
        String result = jedis.set("name", "kaede");
        System.out.println("result = " + result);
        // 获取数据
        String name = jedis.get("name");
        System.out.println("name = " + name);
    }

    @Test
    public void testHash() {
        // 插入hash数据
        Long result1 = jedis.hset("test:jedis:1", "name", "张三");
        System.out.println("result1 = " + result1);
        Map<String, String> map = new HashMap<>();
        map.put("age", "19");
        map.put("height", "170.2");
        Long result2 = jedis.hset("test:jedis:1", map);
        System.out.println("result2 = " + result2);
        // 获取
        Map<String, String> hgetAll = jedis.hgetAll("test:jedis:1");
        System.out.println("hgetAll = " + hgetAll);
    }

    @AfterEach
    void tearDown() {
        // 释放资源
        if (jedis != null) {
            // 连接池中并未关闭，而是将连接归还给连接池
            jedis.close();
        }
    }

}
