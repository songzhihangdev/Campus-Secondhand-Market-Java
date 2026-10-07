package com.campus.agent.llmclient.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 对话补全响应体（OpenAI 兼容），仅保留本客户端需要的字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmResponse {

    /** 本次响应 id */
    @JsonProperty("id")
    private String id;

    /** 候选结果列表（同步非流式一般只有一个，取 choices[0]） */
    @JsonProperty("choices")
    private List<Choice> choices;

    /**
     * 单个候选。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        @JsonProperty("index")
        private Integer index;

        /** 模型生成的消息（可能含 content 或 tool_calls） */
        @JsonProperty("message")
        private AssistantMessage message;

        /** 结束原因，如 stop / tool_calls */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * assistant 角色消息：要么有 content（文本回答），要么有 tool_calls（工具调用）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssistantMessage {

        @JsonProperty("role")
        private String role;

        /** 类型B：最终文本回答 */
        @JsonProperty("content")
        private String content;

        /** 类型A：模型请求的工具调用列表 */
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
    }

    /**
     * 一次工具调用。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {

        /** 调用 id，回传工具结果时需要带上 */
        @JsonProperty("id")
        private String id;

        /** 一般为 "function" */
        @JsonProperty("type")
        private String type;

        /** 被调用的函数信息 */
        @JsonProperty("function")
        private FunctionCall function;
    }

    /**
     * 被调用函数的名称与参数（arguments 为 JSON 字符串，需二次解析）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {

        /** 函数名，即技能 name（skillName） */
        @JsonProperty("name")
        private String name;

        /** 入参 JSON 字符串，例如 {"key":"phone","pageNo":1} */
        @JsonProperty("arguments")
        private String arguments;
    }
}
