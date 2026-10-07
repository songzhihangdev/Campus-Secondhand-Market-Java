package com.campus.agent.llmclient.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 配置，前缀 {@code llm}。
 * <p>
 * 兼容 OpenAI 协议，可同时用于 DeepSeek 与通义千问（DashScope 兼容模式）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /**
     * 服务基础地址（不含 /chat/completions）。
     * DeepSeek：https://api.deepseek.com
     * 通义千问（OpenAI 兼容模式）：https://dashscope.aliyuncs.com/compatible-mode/v1
     */
    private String baseUrl = "https://api.deepseek.com";

    /** API Key，例如 sk-xxxx */
    private String apiKey;

    /** 模型名：DeepSeek 用 deepseek-chat；通义用 qwen-plus / qwen-turbo 等 */
    private String model = "deepseek-chat";

    /** 连接与读取超时（毫秒） */
    private int timeout = 30000;
}
