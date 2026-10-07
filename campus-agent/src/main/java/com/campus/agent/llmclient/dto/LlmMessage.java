package com.campus.agent.llmclient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话消息（OpenAI 兼容格式）。
 * <p>
 * role 常见取值：system / user / assistant / tool。
 * 当 role=tool（工具执行结果回传）时，需同时提供 {@link #toolCallId}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmMessage {

    /** 角色：system / user / assistant / tool */
    @JsonProperty("role")
    private String role;

    /** 文本内容 */
    @JsonProperty("content")
    private String content;

    /** 可选：消息发起者名称（一般用于 tool/function 消息） */
    @JsonProperty("name")
    private String name;

    /** role=tool 时必填，对应上一轮 tool_calls 中某条调用的 id，序列化为 tool_call_id */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /**
     * role=assistant 且模型发起工具调用时必填：本轮请求的工具调用列表，序列化为 tool_calls。
     * 多轮 ReAct 中，必须先写入带 tool_calls 的 assistant 消息，再写对应 tool 结果消息。
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /** 快捷构造：user 消息 */
    public static LlmMessage user(String content) {
        return LlmMessage.builder().role("user").content(content).build();
    }

    /** 快捷构造：system 消息 */
    public static LlmMessage system(String content) {
        return LlmMessage.builder().role("system").content(content).build();
    }

    /** 快捷构造：assistant 消息 */
    public static LlmMessage assistant(String content) {
        return LlmMessage.builder().role("assistant").content(content).build();
    }
}
