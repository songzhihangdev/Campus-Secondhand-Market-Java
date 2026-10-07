package com.campus.agent.scheduler;

/**
 * 会话记忆存储抽象（扩展点）。
 * <p>
 * 当前由 {@link InMemoryAgentMemoryStore} 基于本地 Map 实现，作为 ReAct 的"热上下文"；
 * 历史真源则落在 jsonl 文件（见 FileSessionStore），内存态可随时丢弃并从文件回填。
 * <p>
 * <b>安全约定</b>：所有涉及会话的接口方法都要求传入 {@code userId}，
 * 由实现负责校验会话归属，防止越权访问。
 */
public interface AgentMemoryStore {

    /**
     * 获取会话记忆；不存在则新建并保存。
     *
     * @param sessionId 会话号
     * @param userId    当前登录用户 id（来自 JWT）
     * @return 该会话的记忆
     * @throws com.campus.agent.session.SessionForbiddenException 会话不属于该用户时抛出
     */
    AgentMemory loadOrCreate(String sessionId, Long userId);

    /**
     * 创建一个空会话（"新建对话"）。
     */
    AgentMemory create(Long userId);

    /**
     * 清空并移除指定会话（同时应删除其落盘文件，由实现负责）。
     */
    void clearSession(String sessionId, Long userId);

    /**
     * 当前保存的会话数量（主要用于监控/测试）。
     */
    int sessionCount();
}
