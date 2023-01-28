package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private ShopServiceImpl shopService;

    @Autowired
    private RedisIDWorker redisIDWorker;
    
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService pool = Executors.newFixedThreadPool(100);

    @Test
    public void testSaveShop() {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    public void testIDWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(100);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIDWorker.nextId("order");
                System.out.println(id);
            }
            countDownLatch.countDown();
        };
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            pool.submit(task);
        }
        countDownLatch.await();
        long endTime = System.currentTimeMillis();
        System.out.println("----- cost time: " + (endTime - startTime) + " ms -----");
    }

    @Test
    public void testLoadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺，按照typeId分组，id一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 获取类型id
            Long typeId = entry.getKey();
            // 获取同类型的店铺集合
            List<Shop> shops = entry.getValue();
            // 写入redis GEOADD key 经度 维度 member
            // key = "shop:geo:" + typeId
            String key = SHOP_GEO_KEY + typeId;
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for (Shop shop : shops) {
                // 一个一个添加
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                    shop.getId().toString(), new Point(shop.getX(), shop.getY())
                ));
            }
            // 批量添加
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    public void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                // 发送到redis
                stringRedisTemplate.opsForHyperLogLog().add("hll1",values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hll1");
        System.out.println("count = " + count);
    }
    
}
