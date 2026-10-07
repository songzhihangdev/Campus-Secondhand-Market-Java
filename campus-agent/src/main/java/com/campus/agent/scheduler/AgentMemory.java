package com.campus.agent.scheduler;

import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.llmclient.dto.LlmResponse.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单个会话的记忆：维护该会话的完整消息列表。
 * <p>
 * <b>改造要点（相比初版）</b>：
 * <ul>
 *     <li>新增 {@code ownerUserId} 归属字段：会话属于哪个用户，访问时必须比对，
 *         防止拿别人 sessionId 越权读取聊天记录；</li>
 *     <li><b>移除 token 字段</b>：改为 JWT 透传——身份与凭证每次请求都从
 *         {@code authorization} 头解析，不再需要"把 token 存进记忆跨请求复用"这套机制；</li>
 *     <li>支持从文件回填历史：服务重启后内存态丢失，可从 jsonl 恢复上下文，
 *         让多轮对话得以延续。</li>
 * </ul>
 */
public class AgentMemory {

    /** 所属会话 id */
    private final String sessionId;

    /** 会话归属的用户 id（来自 JWT，不信任前端） */
    private final Long ownerUserId;

    /** 按时间顺序排列的消息（user / assistant / tool） */
    private final List<LlmMessage> messages = new CopyOnWriteArrayList<>();

    /** 创建时间（毫秒时间戳） */
    private final long createTime = System.currentTimeMillis();

    public AgentMemory(String sessionId, Long ownerUserId) {
        this.sessionId = sessionId;
        this.ownerUserId = ownerUserId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public long getCreateTime() {
        return createTime;
    }

    /**
     * 校验当前访问者是否为该会话的归属者。
     *
     * @throws com.campus.agent.session.SessionForbiddenException 归属不匹配时抛出
     */
    public void checkOwner(Long currentUserId) {
        if (currentUserId == null) {
            throw new com.campus.agent.session.SessionForbiddenException("未登录");
        }
        if (ownerUserId != null && !ownerUserId.equals(currentUserId)) {
            throw new com.campus.agent.session.SessionForbiddenException("无权访问该会话");
        }
    }

    /**
     * 会话首条消息若不是 system，则在最前面补一条系统提示（幂等，避免重复注入）。
     */
    public void addSystemIfAbsent(String content) {
        if (messages.isEmpty() || !"system".equals(messages.get(0).getRole())) {
            messages.add(0, LlmMessage.system(content));
        }
    }

    /** 追加一条用户指令 */
    public void addUser(String content) {
        messages.add(LlmMessage.user(content));
    }

    /**
     * 追加 assistant 的工具调用消息（携带 tool_calls，content 通常为 null）。
     * 必须在对应的 tool 结果消息之前写入。
     */
    public void addAssistantToolCalls(List<ToolCall> toolCalls) {
        LlmMessage message = LlmMessage.builder()
                .role("assistant")
                .toolCalls(toolCalls)
                .build();
        messages.add(message);
    }

    /**
     * 追加一条工具执行结果消息（role=tool）。
     */
    public void addToolResult(String toolCallId, String name, String observation) {
        LlmMessage message = LlmMessage.builder()
                .role("tool")
                .content(observation)
                .name(name)
                .toolCallId(toolCallId)
                .build();
        messages.add(message);
    }

    /** 追加 assistant 的最终文本回答 */
    public void addAssistantText(String content) {
        messages.add(LlmMessage.assistant(content));
    }

    /**
     * 返回消息快照（拷贝），供 LlmClient 使用；避免调用方修改内部列表。
     */
    public List<LlmMessage> snapshot() {
        return new ArrayList<>(messages);
    }

    /**
     * 用历史消息整体替换当前上下文（用于从文件回填）。
     * 只接受 user/assistant/tool 三种角色，跳过 system（系统提示会另行注入）。
     */
    public void restoreFrom(List<LlmMessage> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        for (LlmMessage m : history) {
            if (m == null || m.getRole() == null) {
                continue;
            }
            if ("user".equals(m.getRole())
                    || "assistant".equals(m.getRole())
                    || "tool".equals(m.getRole())) {
                messages.add(m);
            }
        }
    }

    /** 清空当前会话记忆（注意：不会删除已落盘的文件） */
    public void clear() {
        messages.clear();
    }

    public int size() {
        return messages.size();
    }
}
