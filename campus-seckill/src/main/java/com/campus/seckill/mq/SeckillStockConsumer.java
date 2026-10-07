package com.campus.seckill.mq;

import com.campus.seckill.dto.SeckillMessage;
import com.campus.seckill.mapper.SeckillItemMapper;
import com.campus.seckill.mapper.SeckillOrderMapper;
import com.campus.seckill.po.SeckillItem;
import com.campus.seckill.po.SeckillOrder;
import com.campus.seckill.service.SeckillRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 秒杀落库消费者：真正创建订单并扣减 DB 库存。
 *
 * <p><b>为什么要 MQ 异步</b>：请求线程只需「加锁 + Redis 扣减 + 投递」，
 * 全部是内存操作（微秒级）；把 DB 写入挪到消费者里，用 MQ 削平流量洪峰。
 *
 * <p><b>异常必须补偿</b>：Redis 已扣减但 DB 落库失败时，
 * 若不回滚 Redis 会导致「少卖」——库存少了但没有订单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillStockConsumer {

    private final SeckillItemMapper seckillItemMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final SeckillRedisService redisService;

    @RabbitListener(queues = "campus.seckill.queue")
    public void onMessage(SeckillMessage msg) {
        if (msg == null || msg.getSeckillId() == null || msg.getUserId() == null) {
            log.warn("秒杀消息格式异常，已忽略");
            return;
        }
        Long seckillId = msg.getSeckillId();
        Long userId = msg.getUserId();

        try {
            // ---- DB 层幂等检查（最后防线之一） ----
            if (seckillOrderMapper.existsByUserAndSeckill(userId, seckillId) > 0) {
                // 重复消息（MQ 至少一次投递），Redis 需回滚因为我们会重复扣
                log.info("秒杀消息重复，已跳过：seckillId={}, userId={}", seckillId, userId);
                redisService.rollbackStock(seckillId, userId);
                return;
            }

            // ---- 第 5 层：DB 条件扣减库存（防超卖最后防线） ----
            int rows = seckillItemMapper.deductStock(seckillId);
            if (rows == 0) {
                log.warn("DB 库存已耗尽，补偿 Redis：seckillId={}, userId={}", seckillId, userId);
                redisService.rollbackStock(seckillId, userId);
                return;
            }

            // ---- 创建秒杀订单 ----
            SeckillItem item = seckillItemMapper.selectById(seckillId);
            SeckillOrder order = new SeckillOrder();
            order.setSeckillId(seckillId);
            order.setItemId(item == null ? null : item.getItemId());
            order.setUserId(userId);
            order.setSeckillPrice(item == null ? null : item.getSeckillPrice());
            order.setCreateTime(LocalDateTime.now());
            seckillOrderMapper.insert(order);

            log.info("秒杀落库成功：seckillId={}, userId={}, orderId={}", seckillId, userId, order.getId());

        } catch (Exception e) {
            // 任何异常都要补偿，否则会少卖
            log.error("秒杀落库失败，补偿库存：seckillId={}, userId={}", seckillId, userId, e);
            redisService.rollbackStock(seckillId, userId);
        }
    }
}
