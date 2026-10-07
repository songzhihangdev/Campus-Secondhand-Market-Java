package com.campus.agent.llmclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 解析后的 LLM 结果（对上层屏蔽原始响应细节）。
 * 区分两种类型：
 * <ul>
 *     <li>{@link ResultType#TOOL_CALLS}：模型要求调用工具，见 {@link #toolCalls}</li>
 *     <li>{@link ResultType#TEXT}：模型给出最终文本，见 {@link #content}</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResult {

    /** 结果类型 */
    private ResultType type;

    /** 类型B：最终文本回答内容 */
    private String content;

    /** 类型A：解析出的工具调用列表 */
    private List<ParsedToolCall> toolCalls;

    /** 结果类型枚举 */
    public enum ResultType {
        /** 工具调用 */
        TOOL_CALLS,
        /** 最终文本 */
        TEXT
    }

    /**
     * 解析后的单个工具调用：技能名 + 结构化参数 + 调用 id。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedToolCall {

        /** 对应 tool_calls[].id，回传结果时使用 */
        private String toolCallId;

        /** 被调用技能名（function.name） */
        private String skillName;

        /** 由 arguments JSON 字符串解析出的参数 Map */
        private Map<String, Object> arguments;
    }

    /** 构造文本结果 */
    public static LlmResult text(String content) {
        return LlmResult.builder()
                .type(ResultType.TEXT)
                .content(content)
                .build();
    }

    /** 构造工具调用结果 */
    public static LlmResult toolCalls(List<ParsedToolCall> toolCalls) {
        return LlmResult.builder()
                .type(ResultType.TOOL_CALLS)
                .toolCalls(toolCalls)
                .build();
    }

    public boolean isToolCalls() {
        return type == ResultType.TOOL_CALLS;
    }

    public boolean isText() {
        return type == ResultType.TEXT;
    }
}
