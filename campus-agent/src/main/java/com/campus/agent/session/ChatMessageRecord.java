package com.campus.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;

import java.io.Serializable;
import java.util.List;

/**
 * jsonl 文件中的一行记录：一条对话消息。
 * <p>
 * <b>注意</b>：本记录同时承担两个职责，因此必须保存 OpenAI 协议的配对字段
 * （{@code toolCallId} / {@code toolCalls}）：
 * <ol>
 *   <li><b>给前端展示</b>：列表、历史、气泡</li>
 *   <li><b>重建 LLM 上下文</b>：{@code FileSessionStore#readForContext} 靠它
 *       把历史消息还原成 {@link com.campus.agent.llmclient.dto.LlmMessage}</li>
 * </ol>
 * 早期版本认为"协议中间态不需要落盘"而跳过保存 tool_calls，
 * 结果历史恢复后 tool 消息找不到对应的 assistant(tool_calls)，
 * DeepSeek 直接返回 422（missing field tool_call_id），<b>整个 ReAct 崩掉</b>。
 * 展示时可以过滤（前端不渲染中间态），但<b>数据必须存</b>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessageRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增序号（从 1 开始，保证文件内顺序） */
    @JsonProperty("seq")
    private Long seq;

    /** 时间戳（毫秒） */
    @JsonProperty("ts")
    private Long ts;

    /** 角色：user / assistant / tool */
    @JsonProperty("role")
    private String role;

    /** 文本内容（tool 消息为 observation 文本） */
    @JsonProperty("content")
    private String content;

    /** 技能名（仅 role=tool时有值），便于前端展示"调用了什么工具" */
    @JsonProperty("name")
    private String name;

    /** 所属会话号（冗余字段，便于单文件导出后仍可识别归属） */
    @JsonProperty("sessionId")
    private String sessionId;

    /**
     * 工具调用 ID（仅 role=tool 时有值）。
     *
     * <p><b>必须有</b>：OpenAI 协议要求每条 tool 消息通过它关联到
     * assistant.tool_calls 中的具体某个调用。缺失则请求体非法，API 返回 422。
     */
    @JsonProperty("toolCallId")
    private String toolCallId;

    /**
     * assistant 携带的工具调用列表（仅中间态 assistant 有值）。
     *
     * <p>落盘后前端<b>不应</b>渲染这条消息（内容为空、只是协议中间态），
     * 但历史恢复时必须重建，否则后续 tool 消息全部失配。
     */
    @JsonProperty("toolCalls")
    private List<ToolCall> toolCalls;

    /**
     * 是否为协议中间态（assistant 携带 tool_calls）。
     *
     * <p>前端据此过滤：这些消息只用于维持上下文链路，没有展示价值。
     * 之前靠"不落盘"来达到过滤效果，代价是破坏了协议配对，故改为显式标记。
     */
    @JsonProperty("intermediate")
    private Boolean intermediate;
}
