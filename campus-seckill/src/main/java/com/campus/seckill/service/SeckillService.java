package com.campus.seckill.service;

import com.campus.seckill.dto.SeckillMessage;
import com.campus.seckill.dto.SeckillResult;
import com.campus.seckill.mapper.SeckillItemMapper;
import com.campus.seckill.mapper.SeckillOrderMapper;
import com.campus.seckill.po.SeckillItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 秒杀核心业务。
 *
 * <p><b>请求线程只做三件事</b>：加锁 → Redis 原子扣减 → 投递 MQ。
 * 真正的落库由 MQ 消费者异步完成，避免高并发下请求线程阻塞在数据库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private static final String LOCK_PREFIX = "campus:seckill:lock:";

    /** 秒杀队列名，需与 RabbitMQConfig 中的队列一致 */
    private static final String SECKILL_QUEUE = "campus.seckill.queue";
    private static final String SECKILL_EXCHANGE = "campus.seckill.exchange";
    private static final String SECKILL_ROUTING_KEY = "campus.seckill";

    private final SeckillRedisService redisService;
    private final SeckillItemMapper seckillItemMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;

    /**
     * 执行秒杀。
     *
     * @param seckillId 秒杀活动 id
     * @param userId    用户 id（来自 JWT，不接受前端传入）
     */
    public SeckillResult doSeckill(Long seckillId, Long userId) {

        // ---- 活动状态校验 ----
        SeckillItem item = seckillItemMapper.selectById(seckillId);
        if (item == null) {
            return SeckillResult.fail("秒杀活动不存在");
        }
        if (!isActive(item)) {
            return SeckillResult.fail("活动未开始或已结束");
        }

        // ---- 第 3 层：Redisson 分布式锁，防同一用户疯狂点击 ----
        RLock lock = redissonClient.getLock(LOCK_PREFIX + seckillId + ":" + userId);
        boolean locked = false;
        try {
            // tryLock(0, 5, SECONDS)：等待 0 秒，拿不到立刻失败，绝不排队
            // 排队会导致线程池被瞬间打满，这是秒杀场景最常见的雪崩原因
            locked = lock.tryLock(0, 5, java.util.concurrent.TimeUnit.SECONDS);
            if (!locked) {
                return SeckillResult.fail("请求过于频繁，请稍后再试");
            }

            // ---- 第 2 层：Redis Lua 原子扣减库存（核心） ----
            int r = redisService.tryDecrStock(seckillId, userId);
            if (r == SeckillRedisService.RESULT_NO_STOCK) {
                return SeckillResult.fail("手慢了，物品已被抢完");
            }
            if (r == SeckillRedisService.RESULT_ALREADY_BUY) {
                return SeckillResult.fail("您已参与过本场秒杀");
            }

            // ---- 第 4 层：投递 MQ，异步落库（削峰） ----
            rabbitTemplate.convertAndSend(SECKILL_EXCHANGE, SECKILL_ROUTING_KEY,
                    new SeckillMessage(seckillId, userId));

            return SeckillResult.ok("抢购请求已提交，正在排队处理");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SeckillResult.fail("请求中断");
        } catch (Exception e) {
            // 投递 MQ 失败必须补偿，否则会少卖
            log.error("秒杀请求处理异常，需补偿库存：seckillId={}, userId={}", seckillId, userId, e);
            redisService.rollbackStock(seckillId, userId);
            return SeckillResult.fail("系统繁忙，请稍后再试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 活动是否在进行中。
     */
    public boolean isActive(SeckillItem item) {
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(item.getStartTime()) && now.isBefore(item.getEndTime());
    }
}
