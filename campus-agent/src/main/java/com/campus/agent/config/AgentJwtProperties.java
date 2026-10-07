package com.campus.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * Agent 侧 JWT 配置，前缀 {@code agent.jwt}。
 * <p>
 * 与微服务侧使用<b>同一个 jks 密钥库</b>，因此本模块签发/解析的 token
 * 与 user-service 签发的完全互通——这是"JWT 透传"方案成立的前提：
 * 前端拿到的 token 可以直接访问各微服务，Agent 也能用同一套密钥校验它。
 */
@Data
@ConfigurationProperties(prefix = "agent.jwt")
public class AgentJwtProperties {

    /** jks 密钥库位置（classpath 相对路径） */
    private Resource location;

    /** 密钥库口令 */
    private String password;

    /** 密钥对别名 */
    private String alias;

    /** token 有效期（与微服务侧保持一致即可） */
    private Duration tokenTTL = Duration.ofMinutes(30L);
}
