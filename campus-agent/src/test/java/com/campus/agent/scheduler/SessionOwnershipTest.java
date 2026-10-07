package com.campus.agent.scheduler;

import com.campus.agent.session.SessionForbiddenException;
import com.campus.agent.session.SessionIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话归属与越权访问测试（安全核心，优先级最高）。
 * <p>
 * 验证三道防线：格式白名单 → 归属校验 → 用户目录隔离。
 */
class SessionOwnershipTest {

    private InMemoryAgentMemoryStore store;

    private final Long alice = 1001L;

    private final Long bob = 2002L;

    @BeforeEach
    void setUp() {
        store = new InMemoryAgentMemoryStore();
    }

    @Test
    @DisplayName("创建会话后，sessionId 与 userId 绑定")
    void create_shouldBindOwner() {
        AgentMemory memory = store.create(alice);
        assertNotNull(memory.getSessionId());
        assertEquals(alice, memory.getOwnerUserId());
    }

    @Test
    @DisplayName("会话主可以正常访问自己的会话")
    void owner_shouldAccessOwnSession() {
        AgentMemory created = store.create(alice);
        AgentMemory loaded = store.loadOrCreate(created.getSessionId(), alice);
        assertEquals(created.getSessionId(), loaded.getSessionId());
    }

    @Test
    @DisplayName("他人访问我的会话：直接拒绝（水平越权防护）")
    void otherUser_shouldBeRejected() {
        AgentMemory created = store.create(alice);
        String sid = created.getSessionId();

        // Bob 拿 Alice 的 sessionId 访问 -> 403
        assertThrows(SessionForbiddenException.class,
                () -> store.loadOrCreate(sid, bob));
    }

    @Test
    @DisplayName("非法 sessionId 格式在取会话时即被拒绝")
    void illegalSidFormat_shouldBeRejected() {
        assertThrows(SessionForbiddenException.class,
                () -> store.loadOrCreate("../../etc/passwd", alice));
    }

    @Test
    @DisplayName("未登录（userId=null）访问任何会话都被拒绝")
    void nullUser_shouldBeRejected() {
        AgentMemory created = store.create(alice);
        assertThrows(SessionForbiddenException.class,
                () -> store.loadOrCreate(created.getSessionId(), null));
    }

    @Test
    @DisplayName("删除他人会话同样被拒绝")
    void clearSession_byOtherUser_shouldBeRejected() {
        AgentMemory created = store.create(alice);
        assertThrows(SessionForbiddenException.class,
                () -> store.clearSession(created.getSessionId(), bob));
    }

    @Test
    @DisplayName("不同用户各自创建的会话互不影响")
    void sessions_shouldBeIsolatedBetweenUsers() {
        AgentMemory a1 = store.create(alice);
        AgentMemory b1 = store.create(bob);

        assertEquals(2, store.sessionCount());
        assertTrue(!a1.getSessionId().equals(b1.getSessionId()));
        assertEquals(alice, a1.getOwnerUserId());
        assertEquals(bob, b1.getOwnerUserId());
    }

    @Test
    @DisplayName("生成的 sid 必然通过格式校验（防御一致性）")
    void generatedSid_shouldAlwaysBeValid() {
        AgentMemory m = store.create(alice);
        assertTrue(SessionIds.isValid(m.getSessionId()));
    }
}
