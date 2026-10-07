package com.campus.seckill.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀订单。
 *
 * <p><b>防重复下单的最后防线</b>：数据库层对 (user_id, seckill_id) 建唯一索引，
 * 即使 Redis 幂等失效或缓存被清空，数据库依然能挡住重复下单。
 */
@Data
@TableName("seckill_order")
public class SeckillOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long seckillId;

    private Long itemId;

    private Long userId;

    /** 成交价（单位：分） */
    private Integer seckillPrice;

    private LocalDateTime createTime;
}
