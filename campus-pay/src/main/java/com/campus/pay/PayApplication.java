package com.campus.pay;

import com.campus.api.config.DefaultFeignConfig;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebFlux;

@EnableFeignClients(basePackages = "com.campus.api.client",defaultConfiguration = DefaultFeignConfig.class)
@MapperScan("com.campus.pay.mapper")
@SpringBootApplication(scanBasePackages = "com.campus")
public class PayApplication {
    public static void main(String[] args) {
        SpringApplication.run(PayApplication.class, args);
    }
}