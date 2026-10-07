package com.campus.seckill.controller;

import com.campus.seckill.dto.SeckillResult;
import com.campus.seckill.mapper.SeckillItemMapper;
import com.campus.seckill.po.SeckillItem;
import com.campus.seckill.service.SeckillRedisService;
import com.campus.seckill.service.SeckillService;
import com.campus.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 秒杀接口。
 *
 * <p><b>鉴权说明</b>：userId 一律从 {@link UserContext} 取（即 JWT 解析结果），
 * <b>绝不接受前端传入</b>——否则用户可以伪造身份抢别人的份额。
 * 网关侧需把 /seckill/** 加入 JWT 校验路径。
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillRedisService redisService;
    private final SeckillItemMapper seckillItemMapper;

    /**
     * 查询当前可参与的活动列表。
     */
    @GetMapping("/sessions")
    public List<SeckillItem> listSessions() {
        List<SeckillItem> all = seckillItemMapper.selectList(null);
        List<SeckillItem> available = new ArrayList<>();
        for (SeckillItem item : all) {
            if (seckillService.isActive(item)) {
                // 附带 Redis 中的实时剩余库存
                Integer left = redisService.getStock(item.getId());
                item.setStock(left != null ? left : item.getStock());
                available.add(item);
            }
        }
        return available;
    }

    /**
     * 查询秒杀活动详情。
     */
    @GetMapping("/{seckillId}")
    public SeckillItem detail(@PathVariable Long seckillId) {
        return seckillItemMapper.selectById(seckillId);
    }

    /**
     * 执行秒杀。
     */
    @PostMapping("/{seckillId}/do")
    public SeckillResult doSeckill(@PathVariable Long seckillId) {
        Long userId = UserContext.getUser();
        if (userId == null) {
            return SeckillResult.fail("未登录");
        }
        log.info("收到秒杀请求：seckillId={}, userId={}", seckillId, userId);
        return seckillService.doSeckill(seckillId, userId);
    }
}
