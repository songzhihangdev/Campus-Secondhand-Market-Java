package com.campus.seckill.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀活动商品。
 *
 * <p>关联 campus-item 服务的 item 表（itemId），本表只存秒杀特有信息。
 */
@Data
@TableName("seckill_item")
public class SeckillItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联的闲置物品 id */
    private Long itemId;

    /** 秒杀价（单位：分，与既有 item.price 保持一致） */
    private Integer seckillPrice;

    /** 秒杀库存 */
    private Integer stock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0未开始 1进行中 2已结束 */
    private Integer status;

    private Long creater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
