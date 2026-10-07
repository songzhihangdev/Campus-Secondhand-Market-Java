package com.campus.cart;

import com.campus.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

//,defaultConfiguration = DefaultFeignConfig.class打印日志（可选）
@EnableFeignClients(basePackages = "com.campus.api.client",defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.campus.cart.mapper")
@SpringBootApplication(scanBasePackages = "com.campus")
public class CartApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}