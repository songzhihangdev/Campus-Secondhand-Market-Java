package com.campus.agent.config;

import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 工厂：按每个技能的超时时间创建 RestTemplate。
 * <p>
 * 抽成接口是为了便于单元测试（测试中可返回绑定了 MockRestServiceServer 的实例）。
 */
public interface RestTemplateFactory {

    /**
     * @param readTimeoutMs 读超时（毫秒），取自 HttpSkillMeta.timeout
     * @return 配置好超时的 RestTemplate
     */
    RestTemplate create(int readTimeoutMs);
}
