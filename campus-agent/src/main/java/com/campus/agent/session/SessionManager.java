package com.campus.agent.session;

import com.campus.agent.config.AgentSessionProperties;
import com.campus.agent.llmclient.dto.LlmMessage;
import com.campus.agent.scheduler.AgentMemory;
import com.campus.agent.scheduler.AgentMemoryStore;
import com.campus.agent.scheduler.InMemoryAgentMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话门面：把"内存热上下文"与"文件历史"粘合成对外可用的会话能力。
 * <p>
 * 对外只暴露与用户身份相关的操作，<b>所有方法都要求传入 userId（来自 JWT）</b>，
 * 内部完成格式校验、归属校验、文件回填与落盘。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionManager {

    private final InMemoryAgentMemoryStore memoryStore;

    private final FileSessionStore fileStore;

    private final AgentSessionProperties properties;

    /** 全局自增序号，保证同一会话内 seq 单调递增 */
    private final AtomicLong seqGenerator = new AtomicLong(0L);

    // ==================== 会话生命周期 ====================

    /**
     * 新建会话（对应前端"新建对话"按钮）。
     *
     * @return 新会话的 sessionId
     */
    public String createSession(Long userId) {
        AgentMemory memory = memoryStore.create(userId);
        String sessionId = memory.getSessionId();
        // 建一个空文件，使会话立刻出现在列表中
        fileStore.append(userId, sessionId, ChatMessageRecord.builder()
                .seq(nextSeq())
                .ts(System.currentTimeMillis())
                .role("system")
                .content("会话创建")
                .sessionId(sessionId)
                .build());
        return sessionId;
    }

    /**
     * 获取（必要时创建并回填）会话上下文，供 ReAct 调度使用。
     * <p>
     * 若内存中没有但文件存在（服务重启后），会把历史回填进内存，让多轮对话得以延续。
     */
    public AgentMemory openSession(String sessionId, Long userId) {
        SessionIds.requireValid(sessionId);
        // 归属校验在 loadOrCreate 内部完成
        AgentMemory memory = memoryStore.loadOrCreate(sessionId, userId);

        if (memory.size() == 0 && fileStore.exists(userId, sessionId)) {
            List<LlmMessage> history = fileStore.readForContext(userId, sessionId);
            if (!history.isEmpty()) {
                memory.restoreFrom(history);
                log.info("会话[{}]已从文件回填 {} 条历史消息", sessionId, history.size());
            }
        }
        return memory;
    }

    /**
     * 列出当前用户的会话。
     */
    public List<SessionSummary> listSessions(Long userId) {
        return fileStore.listSessions(userId);
    }

    /**
     * 读取某会话的完整历史（前端查看聊天记录）。
     */
    public List<ChatMessageRecord> history(String sessionId, Long userId) {
        // 归属校验：会话文件位于 userId 目录下，跨用户访问自然读不到；
        // 这里额外做一次显式校验，让"越权"意图在代码上就明确被拒
        SessionIds.requireValid(sessionId);
        return fileStore.readHistory(userId, sessionId);
    }

    /**
     * 删除会话：同时清内存与文件。
     */
    public void deleteSession(String sessionId, Long userId) {
        SessionIds.requireValid(sessionId);
        memoryStore.clearSession(sessionId, userId);
        fileStore.delete(userId, sessionId);
        log.info("删除会话：sessionId={}, userId={}", sessionId, userId);
    }

    // ==================== 落盘 ====================

    /**
     * 把本轮新增的消息追加到会话文件。
     *
     * @param newMessages 本轮新增的消息（user / assistant / tool）
     */
    public void persistRound(String sessionId, Long userId, List<LlmMessage> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) {
            return;
        }
        List<ChatMessageRecord> records = new ArrayList<>(newMessages.size());
        long now = System.currentTimeMillis();
        for (LlmMessage m : newMessages) {
            if (m == null || m.getRole() == null) {
                continue;
            }
            boolean intermediate = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
            // 【重要】assistant(tool_calls) 这条协议中间态**必须落盘**。
            //   早期实现认为"它是中间态、不给前端看"就直接 continue，
            //   导致 jsonl 里只剩孤立的 tool 消息 —— 历史恢复后
            //   OpenAI/DeepSeek 因 tool 消息找不到对应 tool_call_id 而返回 422，
            //   整个 ReAct 循环崩溃。
            //   正确做法：**存下来 + 打 intermediate 标记**，
            //   前端按标记过滤展示，readForContext 则用它重建配对关系。
            records.add(ChatMessageRecord.builder()
                    .seq(nextSeq())
                    .ts(now)
                    .role(m.getRole())
                    .content(m.getContent())
                    .name(m.getName())
                    // tool 消息靠它关联到具体的 tool_call，缺了就是 422
                    .toolCallId(m.getToolCallId())
                    .toolCalls(m.getToolCalls())
                    // Boolean 字段，@JsonInclude(NON_NULL) 会自动忽略 null，
                    // 因此 false 也要写成 null 才不会写进 jsonl（保持文件精简）
                    .intermediate(intermediate ? Boolean.TRUE : null)
                    .sessionId(sessionId)
                    .build());
        }
        fileStore.appendAll(userId, sessionId, records);
    }

    private long nextSeq() {
        return seqGenerator.incrementAndGet();
    }

    /**
     * 供测试/监控查看当前内存会话数。
     */
    public int activeSessionCount() {
        return memoryStore.sessionCount();
    }

    /**
     * 暴露 store 接口类型，便于其它组件按接口注入。
     */
    public AgentMemoryStore memoryStore() {
        return memoryStore;
    }
}
