package com.campus.agent.apitest;

import com.campus.agent.config.SkillRegistry;
import com.campus.agent.llmclient.client.LlmClient;
import com.campus.agent.llmclient.converter.SkillToToolConverter;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.llmclient.dto.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

/**
 * 真实大模型联调测试：会发起真实网络请求并消耗 token。
 * <p>
 * <b>默认不执行</b>：需要真实 API Key 与外网访问，CI 环境必然失败（会返回 401）。
 * 联调时先设置环境变量 {@code LLM_API_KEY}，再把
 * {@code @EnabledIfEnvironmentVariable} 换成 {@code @EnabledIf} 判断该变量是否存在即可启用。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".+")
public class RealLlmTest {

    @Autowired
    LlmClient client;

    @Autowired
    SkillToToolConverter converter;

    @Autowired
    SkillRegistry registry;

    @Test
    void realText() {
        // 类型B：纯文本
        LlmResult r = client.chat(List.of(LlmMessage.user("用一句话介绍你自己")));
        System.out.println(r.getType());
        System.out.println(r.getContent());
    }

    @Test
    void realToolCall() {
        // 把内存技能转成 tools，让模型决定是否调用
        List<ToolDefinition> tools = converter.convertAll(new ArrayList<>(registry.allLlmSkills()));
        LlmResult r = client.chat(List.of(LlmMessage.user("帮我搜一下华为手机")), tools);
        System.out.println(r.getType());           // 期望 TOOL_CALLS
        if (r.isToolCalls()) {
            r.getToolCalls().forEach(c ->
                    System.out.println(c.getSkillName() + " -> " + c.getArguments()));
        }
    }
}
