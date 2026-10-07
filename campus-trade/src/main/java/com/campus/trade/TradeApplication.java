package com.campus.trade;

import com.campus.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients(basePackages = "com.campus.api.client",defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.campus.trade.mapper")
@SpringBootApplication(scanBasePackages = "com.campus")
public class TradeApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeApplication.class, args);
    }
}