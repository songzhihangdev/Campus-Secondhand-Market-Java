package com.campus.agent;

import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.config.ContextCompressProperties;
import com.campus.agent.config.MallProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * hm-agent 启动类。
 * <p>
 * 模块能力：技能加载（SkillRegistry）+ 会话记忆与落盘（AgentMemory/FileSessionStore）
 * + 微服务 HTTP 执行（MallApiExecutor）+ JWT 鉴权（AuthInterceptor）
 * + 上下文压缩（ContextCompressor）
 * + ReAct 调度（ReActAgentScheduler，OpenAI 兼容 LLM 客户端）。
 * <p>
 * 对外接口全部要求登录：前端先经 user-service 登录拿到 JWT，
 * 之后每个请求携带 {@code authorization} 头；Agent 校验后把 JWT 原样透传给各微服务。
 */
@SpringBootApplication
@EnableConfigurationProperties({
        MallProperties.class,
        AgentSessionProperties.class,
        ContextCompressProperties.class
})
public class CampusAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CampusAgentApplication.class, args);
    }
}
