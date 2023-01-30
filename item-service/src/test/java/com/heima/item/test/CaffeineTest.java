package com.heima.item.test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;

import java.time.Duration;

public class CaffeineTest {

    /*
      基本用法测试
     */
    @Test
    void testBasicOps() {
        // 构建cache对象
        Cache<String, String> cache = Caffeine.newBuilder().build();

        // 存数据
        cache.put("001", "zhangsan");

        // 取数据
        String person = cache.getIfPresent("001");
        System.out.println("person = " + person);

        // 取数据，包含两个参数：
        // 参数一：缓存的key
        // 参数二：Lambda表达式，表达式参数就是缓存的key，方法体是查询数据库的逻辑
        // 优先根据key查询JVM缓存，如果未命中，则执行参数二的Lambda表达式
        String person2 = cache.get("002", key -> {
            // 根据key去数据库查询数据
            return "lisi";
        });
        System.out.println("person2 = " + person2);
    }

    /*
     基于大小设置驱逐策略：
     */
    @Test
    void testEvictByNum() throws InterruptedException {
        // 创建缓存对象
        Cache<String, String> cache = Caffeine.newBuilder()
                // 设置缓存大小上限为1
                .maximumSize(1)
                .build();
        // 存数据
        cache.put("001", "zhangsan");
        cache.put("002", "lisi");
        cache.put("003", "wangwu");
        // 延迟10ms，给清理线程一点时间
        Thread.sleep(10L);
        // 获取数据
        System.out.println("person1: " + cache.getIfPresent("001"));
        System.out.println("person2: " + cache.getIfPresent("002"));
        System.out.println("person3: " + cache.getIfPresent("003"));
    }

    /*
     基于时间设置驱逐策略：
     */
    @Test
    void testEvictByTime() throws InterruptedException {
        // 创建缓存对象
        Cache<String, String> cache = Caffeine.newBuilder()
            // 设置缓存有效期为2秒
                .expireAfterWrite(Duration.ofSeconds(2))
                .build();
        // 存数据
        cache.put("001", "zhangsan");
        cache.put("003", "wangwu");
        Thread.sleep(1200L);
        cache.put("002", "lisi");
        cache.put("003", "wangwu");
        // 获取数据
        System.out.println("person1: " + cache.getIfPresent("001"));
        System.out.println("person2: " + cache.getIfPresent("002"));
        System.out.println("person3: " + cache.getIfPresent("003"));
        // 休眠一会儿
        Thread.sleep(1200L);
        System.out.println("person1: " + cache.getIfPresent("001"));
        System.out.println("person2: " + cache.getIfPresent("002"));
        System.out.println("person3: " + cache.getIfPresent("003"));
    }

}
