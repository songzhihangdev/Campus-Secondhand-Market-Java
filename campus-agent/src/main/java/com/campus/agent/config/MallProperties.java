package com.campus.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商城相关配置，前缀 {@code mall}，所有硬编码项均在此集中管理。
 */
@Data
@ConfigurationProperties(prefix = "mall")
public class MallProperties {

    /** 商城服务基础地址（不含接口路径），例如 http://localhost:8080 */
    private String baseUrl = "http://localhost:8080";

    /** 全局连接超时（毫秒），读超时取每个技能 meta.timeout */
    private int connectTimeout = 30000;

    /** 技能配置文件位置（classpath 相对路径） */
    private Skills skills = new Skills();

    @Data
    public static class Skills {
        /** 面向大模型的技能定义文件 */
        private String llmDef = "skills/llm_skill_def.json";
        /** HTTP 元数据文件 */
        private String httpMeta = "skills/http_meta.json";
    }
}
