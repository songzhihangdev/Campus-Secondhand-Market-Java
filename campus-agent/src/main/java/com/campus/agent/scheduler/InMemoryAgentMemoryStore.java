package com.campus.agent.scheduler;

import com.campus.agent.session.SessionIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 {@link ConcurrentHashMap} 的内存会话热缓存。
 * <p>
 * <b>定位变化</b>：它现在只是"跑 ReAct 需要的消息列表缓存"，不再是会话的唯一存储。
 * 真正的历史记录落在 jsonl 文件里，因此本 Map 丢失（重启/TTL 过期）不会导致数据丢失——
 * {@link FileSessionStore} 可以把上下文回填进来。
 * <p>
 * 归属校验：会话号与 userId 绑定，取用时若 owner 与当前用户不一致，直接拒绝。
 */
@Slf4j
@Component
public class InMemoryAgentMemoryStore implements AgentMemoryStore {

    /** key = sessionId，value = 会话记忆 */
    private final Map<String, AgentMemory> sessions = new ConcurrentHashMap<>();

    @Override
    public AgentMemory loadOrCreate(String sessionId, Long userId) {
        // 1. 会话号格式必须合法（防路径穿越；因为本类不碰文件，这里做基础防御）
        SessionIds.requireValid(sessionId);

        AgentMemory memory = sessions.get(sessionId);
        if (memory != null) {
            // 2. 已有会话：校验归属，防越权读取他人上下文
            memory.checkOwner(userId);
            return memory;
        }

        // 3. 内存中没有：可能是首次请求，也可能是重启后从文件恢复。
        //    这里先建一个空会话（归属 = 当前用户），历史回填由 SessionManager 负责。
        AgentMemory created = new AgentMemory(sessionId, userId);
        AgentMemory existing = sessions.putIfAbsent(sessionId, created);
        if (existing != null) {
            // 并发下别人先建好了，以先建者为准并校验归属
            existing.checkOwner(userId);
            return existing;
        }
        return created;
    }

    @Override
    public AgentMemory create(Long userId) {
        String sessionId = SessionIds.generate();
        AgentMemory memory = new AgentMemory(sessionId, userId);
        sessions.put(sessionId, memory);
        log.info("创建新会话：sessionId={}, userId={}", sessionId, userId);
        return memory;
    }

    @Override
    public void clearSession(String sessionId, Long userId) {
        AgentMemory memory = sessions.get(sessionId);
        if (memory != null) {
            memory.checkOwner(userId);
        }
        sessions.remove(sessionId);
    }

    @Override
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 把已从文件恢复好上下文的会话放入缓存（供 SessionManager 回填时调用）。
     */
    public void put(AgentMemory memory) {
        sessions.put(memory.getSessionId(), memory);
    }
}
