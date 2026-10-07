package com.campus.agent.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话号生成与格式校验测试（安全相关，优先级高）。
 * <p>
 * 重点验证<b>不可枚举</b>与<b>防路径穿越</b>两项性质。
 */
class SessionIdsTest {

    @Test
    @DisplayName("生成的会话号必须是 32 位小写 hex")
    void generate_shouldReturn32HexChars() {
        String sid = SessionIds.generate();
        assertEquals(32, sid.length());
        assertTrue(sid.matches("^[0-9a-f]{32}$"), "会话号必须是小写 hex：" + sid);
    }

    @Test
    @DisplayName("每次生成的会话号互不相同（不可枚举）")
    void generate_shouldBeUnique() {
        String a = SessionIds.generate();
        String b = SessionIds.generate();
        assertNotEquals(a, b);
    }

    @Test
    @DisplayName("合法会话号通过校验")
    void isValid_shouldAcceptLegalSid() {
        assertTrue(SessionIds.isValid("a3f9c2e1b8d4477890abcdef12345678"));
    }

    @Test
    @DisplayName("路径穿越类输入必须被拒绝")
    void isValid_shouldRejectPathTraversal() {
        // 这些是攻击者可能构造的输入，全部应判为非法
        assertFalse(SessionIds.isValid("../../etc/passwd"));
        assertFalse(SessionIds.isValid("..%2F..%2Fetc%2Fpasswd"));
        assertFalse(SessionIds.isValid("aaaa/../bbbb"));
        assertFalse(SessionIds.isValid("aaaa\\bbbb"));
        assertFalse(SessionIds.isValid("/etc/passwd"));
    }

    @Test
    @DisplayName("null / 空 / 超长 / 含大写 等均判为非法")
    void isValid_shouldRejectMalformed() {
        assertFalse(SessionIds.isValid(null));
        assertFalse(SessionIds.isValid(""));
        assertFalse(SessionIds.isValid("A3F9C2E1B8D4477890ABCDEF12345678")); // 大写
        assertFalse(SessionIds.isValid("a3f9c2e1b8d4477890abcdef1234567"));   // 31 位
        assertFalse(SessionIds.isValid("a3f9c2e1b8d4477890abcdef123456789")); // 33 位
        assertFalse(SessionIds.isValid("g3f9c2e1b8d4477890abcdef12345678"));  // 含非 hex 字符
    }

    @Test
    @DisplayName("requireValid 对非法输入抛 SessionForbiddenException")
    void requireValid_shouldThrowOnIllegal() {
        assertThrows(SessionForbiddenException.class,
                () -> SessionIds.requireValid("../../etc/passwd"));
        // 合法输入不抛异常
        SessionIds.requireValid("a3f9c2e1b8d4477890abcdef12345678");
    }
}
