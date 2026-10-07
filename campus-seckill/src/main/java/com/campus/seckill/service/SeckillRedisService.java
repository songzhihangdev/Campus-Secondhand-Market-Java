package com.campus.seckill.service;

import com.campus.seckill.po.SeckillItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀库存的 Redis 操作封装。
 *
 * <p><b>核心是 {@link #tryDecrStock}：用 Lua 脚本保证「幂等检查 + 库存检查 + 扣减」原子完成</b>，
 * 这是整套防超卖方案里最关键的一环。
 */
@Slf4j
@Service
public class SeckillRedisService {

    /** 扣减结果：库存不足 */
    public static final int RESULT_NO_STOCK = 0;
    /** 扣减结果：成功 */
    public static final int RESULT_SUCCESS = 1;
    /** 扣减结果：重复购买 */
    public static final int RESULT_ALREADY_BUY = 2;

    private static final String KEY_PREFIX = "campus:seckill:";
    private static final String KEY_STOCK = KEY_PREFIX + "stock:";
    private static final String KEY_USERS = KEY_PREFIX + "users:";

    private static final DefaultRedisScript<Long> STOCK_LUA;

    static {
        STOCK_LUA = new DefaultRedisScript<>();
        STOCK_LUA.setResultType(Long.class);
        STOCK_LUA.setLocation(new ClassPathResource("lua/seckill_stock.lua"));
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 原子扣减秒杀库存。
     *
     * @param seckillId 秒杀活动 id
     * @param userId    用户 id
     * @return {@link #RESULT_SUCCESS} / {@link #RESULT_NO_STOCK} / {@link #RESULT_ALREADY_BUY}
     */
    public int tryDecrStock(Long seckillId, Long userId) {
        try {
            Long result = redisTemplate.execute(
                    STOCK_LUA,
                    Arrays.asList(KEY_STOCK + seckillId, KEY_USERS + seckillId),
                    String.valueOf(userId));
            int code = result == null ? RESULT_NO_STOCK : result.intValue();
            log.debug("秒杀扣减结果：seckillId={}, userId={}, code={}", seckillId, userId, code);
            return code;
        } catch (Exception e) {
            // Redis 不可用时不能放行（放行会导致超卖），必须失败
            log.error("秒杀库存扣减异常：seckillId={}, userId={}", seckillId, userId, e);
            return RESULT_NO_STOCK;
        }
    }

    /**
     * 补偿：Redis 库存回滚 + 移除用户标记。
     *
     * <p>触发场景：Redis 扣减成功但 DB 落库失败（MQ 消费异常），
     * 若不回滚会「少卖」——Redis 少了库存但 DB 没有对应订单。
     */
    public void rollbackStock(Long seckillId, Long userId) {
        try {
            redisTemplate.opsForValue().increment(KEY_STOCK + seckillId);
            redisTemplate.opsForSet().remove(KEY_USERS + seckillId, String.valueOf(userId));
            log.warn("秒杀库存已补偿回滚：seckillId={}, userId={}", seckillId, userId);
        } catch (Exception e) {
            log.error("补偿回滚失败，需人工介入：seckillId={}, userId={}", seckillId, userId, e);
        }
    }

    /**
     * 活动开始时初始化 Redis 库存。
     */
    public void initStock(Long seckillId, Integer stock) {
        redisTemplate.opsForValue().set(KEY_STOCK + seckillId, String.valueOf(stock));
        // 活动未开始时也要先清空已购集合，避免上一场的数据污染
        redisTemplate.delete(KEY_USERS + seckillId);
        log.info("秒杀库存已初始化：seckillId={}, stock={}", seckillId, stock);
    }

    /**
     * 活动结束时清理 Redis key（用完即清，不浪费内存）。
     */
    public void clearStock(Long seckillId) {
        redisTemplate.delete(Arrays.asList(KEY_STOCK + seckillId, KEY_USERS + seckillId));
        log.info("秒杀 Redis key 已清理：seckillId={}", seckillId);
    }

    /**
     * 查询当前剩余库存（供前端展示"剩余 N 件"）。
     */
    public Integer getStock(Long seckillId) {
        String v = redisTemplate.opsForValue().get(KEY_STOCK + seckillId);
        return v == null ? null : Integer.valueOf(v);
    }

    /**
     * 缓存秒杀配置，避免每次请求都查库。
     */
    public void cacheSeckillItem(SeckillItem item, long ttlMinutes) {
        redisTemplate.opsForHash().put(KEY_PREFIX + "config:" + item.getId(), "item", String.valueOf(item.getId()));
        redisTemplate.expire(KEY_PREFIX + "config:" + item.getId(), ttlMinutes, TimeUnit.MINUTES);
    }
}
