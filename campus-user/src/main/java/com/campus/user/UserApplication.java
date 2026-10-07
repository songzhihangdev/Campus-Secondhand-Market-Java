package com.campus.user;

import com.campus.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients(basePackages = "com.campus.api.client",defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.campus.user.mapper")
@SpringBootApplication(scanBasePackages = "com.campus")
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}