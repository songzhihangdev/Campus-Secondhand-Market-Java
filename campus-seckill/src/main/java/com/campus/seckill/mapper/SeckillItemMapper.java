package com.campus.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.seckill.po.SeckillItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SeckillItemMapper extends BaseMapper<SeckillItem> {

    /**
     * 条件扣减库存（乐观写法，最后一道防超卖防线）。
     * <p>只有当 stock &gt; 0 时才会更新，影响行数为 0 说明已被抢完。
     *
     * @return 影响行数（1=扣减成功，0=库存不足）
     */
    int deductStock(@Param("seckillId") Long seckillId);
}
