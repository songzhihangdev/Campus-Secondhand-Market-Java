package com.campus.agent.llmclient.client;

import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResult;
import com.campus.agent.llmclient.dto.ToolDefinition;

import java.util.List;

/**
 * LLM 客户端抽象。
 * <p>
 * 只负责“与大模型对话并解析结果”，不包含 Agent 调度、商城执行等逻辑。
 */
public interface LlmClient {

    /**
     * 带工具定义的对话。
     *
     * @param messages 消息列表
     * @param tools    工具定义列表（可为 null/空，表示不启用 Function-Calling）
     * @return 解析结果：可能是工具调用，也可能是最终文本
     */
    LlmResult chat(List<LlmMessage> messages, List<ToolDefinition> tools);

    /**
     * 不带工具的纯文本对话。
     */
    default LlmResult chat(List<LlmMessage> messages) {
        return chat(messages, null);
    }
}
