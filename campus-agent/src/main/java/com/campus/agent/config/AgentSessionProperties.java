package com.campus.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 会话与历史记录配置，前缀 {@code agent.session}。
 */
@Data
@ConfigurationProperties(prefix = "agent.session")
public class AgentSessionProperties {

    /**
     * 对话记录落盘根目录。
     * <p>
     * 组织形式：{@code {root}/{userId}/{sessionId}.jsonl}。
     * userId <b>只从 JWT 解析得到</b>，绝不使用前端传入值，避免路径穿越与越权。
     */
    private String rootDir = "D:/JavaProject/261005/UserContext";

    /**
     * 会话在内存中的保留时长；超过后从 InMemoryAgentMemoryStore 移除。
     * <p>
     * 注意：内存态是"热缓存"用于跑 ReAct，历史真源在 jsonl 文件，
     * 因此即使内存被清理，历史记录也不会丢（下一次访问会从文件回填）。
     */
    private Duration memoryTtl = Duration.ofHours(2L);
}
