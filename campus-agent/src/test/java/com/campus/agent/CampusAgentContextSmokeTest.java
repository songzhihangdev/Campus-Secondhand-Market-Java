package com.campus.agent;

import com.campus.agent.llmclient.client.LlmClient;
import com.campus.agent.llmclient.converter.SkillToToolConverter;
import com.campus.agent.config.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring 上下文冒烟测试：验证 hm-agent 全部 Bean（含 LLM 客户端）能成功装配、启动不报错。
 * 不会真正发起任何 HTTP 请求。
 */
@SpringBootTest
class CampusAgentContextSmokeTest {

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private SkillToToolConverter skillToToolConverter;

    @Autowired
    private SkillRegistry skillRegistry;

    @Test
    void context_shouldLoadAndBeansPresent() {
        assertNotNull(llmClient);
        assertNotNull(skillToToolConverter);
        assertNotNull(skillRegistry);
    }
}
