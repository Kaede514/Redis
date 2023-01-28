package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIDWorker redisIDWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    // private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    /* private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                // 1.获取队列中的订单信息，无元素时take方法会阻塞
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    } */

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 2.1.获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析消息中的订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.获取成功，创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.ACK确认 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                // 2.判断消息获取是否成功
                if (list == null || list.isEmpty()) {
                    // 2.1.获取失败，说明pending-list中没有异常消息，结束循环
                    break;
                }
                // 解析消息中的订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.获取成功，创建订单
                handleVoucherOrder(voucherOrder);
                // 4.ACK确认 XACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge("stream.orders", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try { TimeUnit.MILLISECONDS.sleep(20); } catch (InterruptedException ex) { ex.printStackTrace();}
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 主线程中的子线程无法直接在threadLocal中获取
        Long userId = voucherOrder.getUserId();
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:voucher:" + userId);
        // 获取锁
        // 默认情况下，获取锁不等待，30s后自动释放
        boolean isLock = lock.tryLock();
        // 判断释放获取锁成功
        if (!isLock) {
            // 获取锁失败
            log.error("不允许重复下单！");
            return;
        }
        // 获取当前对象的代理对象，子线程中无法获取
        VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
        // 获取锁成功
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    // 在当前类初始化完毕后执行
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    public static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        // 初始化脚本
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
    }

    @Override
    public Result setkillVoucher(Long voucherId) {
        // 1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        long orderId  = redisIDWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
            String.valueOf(voucherId), String.valueOf(userId), String.valueOf(orderId));
        // 2.判断结果是否为0
        if (result == 1) {
            // 2.1.为1，库存不足
            return Result.fail("库存不足！");
        } else if (result == 2) {
            // 2.2.为2，重复下单，将下单信息保存至阻塞队列
            return Result.fail("不允许重复下单！");
        }
        // 2.3.为0，有购买资格
        // 3.返回订单id
        return Result.ok(orderId);
    }

    /* @Override
    public Result setkillVoucher(Long voucherId) {
        // 1.执行lua脚本
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
            String.valueOf(voucherId), userId.toString());
        // 2.判断结果是否为0
        if (result == 1) {
            // 2.1.为1，库存不足
            return Result.fail("库存不足！");
        } else if (result == 2) {
            // 2.2.为2，重复下单，将下单信息保存至阻塞队列
            return Result.fail("不允许重复下单！");
        }
        // 2.3.为0，有购买资格
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId  = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 将下单信息保存至阻塞队列
        orderTasks.add(voucherOrder);
        // 3.返回订单id
        return Result.ok(orderId);
    } */

    @Transactional
    public void createVoucherOrder(@NotNull VoucherOrder voucherOrder) {
        long userId = voucherOrder.getUserId();
        long voucherId = voucherOrder.getVoucherId();
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 4.2.判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次！");
            return;
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
            .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            log.error("库存不足！");
            return;
        }
        // 6.创建订单
        save(voucherOrder);
    }

    /* @Override
    public Result setkillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始和结束
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        } else if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束！");
        }
        // 3.判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足！");
        }
        // 4.一人一单
        // 4.1.查询订单
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock redisLock = new SimpleRedisLock("voucher:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:voucher:" + userId);
        // 获取锁
        // boolean isLock = redisLock.tryLock(5);
         *//**参数
         * 获取锁的最大等待时间(期间会重试)
         * 锁自动释放时间
         * 时间单位
         *//*
        // 默认情况下，获取锁不等待，30s后自动释放
        boolean isLock = lock.tryLock();
        // 判断释放获取锁成功
        if (!isLock) {
            // 获取锁失败
            return Result.fail("不允许重复下单！");
        }
        // 获取锁成功
        try {
            // 获取当前对象的代理对象
            VoucherOrderServiceImpl proxy = (VoucherOrderServiceImpl) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } finally {
            // 释放锁
            // redisLock.unlock();
            lock.unlock();
        }
    } */

    /* @Transactional
    public Result createVoucherOrder(Long voucherId, Long userId) {
        long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 4.2.判断是否存在
        if (count > 0) {
            return Result.fail("您已经下单过了");
        }
        // 5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
            .eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            return Result.fail("库存不足！");
        }
        // 6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId  = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 7.返回订单id
        return Result.ok(orderId);
    } */

}
