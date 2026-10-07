package com.campus.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 校园二手秒杀服务。
 *
 * <p><b>为什么独立成一个模块</b>：秒杀是瞬时高并发场景，资源需求与日常业务差异极大，
 * 独立部署便于单独扩容与压测，也避免占用商品服务的连接池。
 *
 * <p><b>防超卖的五层防护</b>：
 * <ol>
 *   <li>网关限流（RequestRateLimiter）—— 最外层削峰</li>
 *   <li>Redis Lua 原子扣减库存 —— 核心，杜绝超卖</li>
 *   <li>Redisson 分布式锁 —— 防同一用户重复点击</li>
 *   <li>MQ 异步落库 —— 削峰，避免请求线程阻塞在 DB</li>
 *   <li>DB 条件更新 + 唯一索引 —— 最后防线，即使 Redis 失效也不重复下单</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.campus")
@EnableDiscoveryClient
@MapperScan("com.campus.seckill.mapper")
public class CampusSeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusSeckillApplication.class, args);
    }
}
