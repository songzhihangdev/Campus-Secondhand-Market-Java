package com.campus.agent.llmclient.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 对话补全请求体（OpenAI /chat/completions 兼容）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmRequest {

    /** 模型名，例如 deepseek-chat / qwen-plus */
    @JsonProperty("model")
    private String model;

    /** 对话消息列表 */
    @JsonProperty("messages")
    private List<LlmMessage> messages;

    /** 可选：工具定义列表，不传表示不启用 Function-Calling */
    @JsonProperty("tools")
    private List<ToolDefinition> tools;

    /** 可选：工具选择策略，如 "auto"；默认由模型自行决定 */
    @JsonProperty("tool_choice")
    private String toolChoice;

    /** 可选：采样温度 */
    @JsonProperty("temperature")
    private Double temperature;

    /** 是否流式，本客户端固定为 false（同步调用） */
    @JsonProperty("stream")
    private Boolean stream;
}
