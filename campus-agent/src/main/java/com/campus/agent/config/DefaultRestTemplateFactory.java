package com.campus.agent.config;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * 默认 RestTemplate 工厂实现。
 * <p>
 * 使用 JDK 自带的 {@link SimpleClientHttpRequestFactory}（基于 HttpURLConnection），
 * 连接超时取全局配置 mall.connect-timeout，读超时取每个技能 meta.timeout，
 * 从而在不引入 Apache HttpClient 等额外依赖的前提下满足“按技能设置超时”。
 */
@Component
public class DefaultRestTemplateFactory implements RestTemplateFactory {

    private final MallProperties mallProperties;

    public DefaultRestTemplateFactory(MallProperties mallProperties) {
        this.mallProperties = mallProperties;
    }

    @Override
    public RestTemplate create(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // 建立连接阶段超时：全局统一配置
        factory.setConnectTimeout(mallProperties.getConnectTimeout());
        // 读取响应阶段超时：使用每个技能 meta.timeout
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
