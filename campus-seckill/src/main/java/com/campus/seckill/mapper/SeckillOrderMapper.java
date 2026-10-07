package com.campus.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.seckill.po.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 判断用户是否已对该秒杀商品下过单（DB 层幂等检查）。
     */
    int existsByUserAndSeckill(@Param("userId") Long userId,
                               @Param("seckillId") Long seckillId);
}
