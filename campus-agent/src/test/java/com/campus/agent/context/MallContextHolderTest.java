package com.campus.agent.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * MallContextHolder 单元测试：验证 ThreadLocal 的存取与清理。
 */
class MallContextHolderTest {

    @AfterEach
    void tearDown() {
        MallContextHolder.clear();
    }

    @Test
    void token_shouldBeStoredAndRead() {
        assertNull(MallContextHolder.getToken());

        MallContextHolder.setToken("hello-token");
        assertEquals("hello-token", MallContextHolder.getToken());
    }

    @Test
    void clear_shouldRemoveToken() {
        MallContextHolder.setToken("hello-token");
        MallContextHolder.clear();
        assertNull(MallContextHolder.getToken());
    }
}
