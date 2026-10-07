package com.campus.agent.session;

import java.util.UUID;

/**
 * 会话标识与归属校验工具。
 * <p>
 * <b>安全要点</b>：
 * <ol>
 *     <li><b>不可枚举</b>：sessionId 用 UUID v4 去横线（32 位小写 hex），
 *         攻击者无法靠递增或时间戳猜出他人会话号；</li>
 *     <li><b>防路径穿越</b>：会话号会被拼进文件路径 {@code {root}/{userId}/{sid}.jsonl}，
 *         因此<b>必须先做格式白名单校验</b>。像 {@code ../../etc/passwd} 这类输入
 *         会直接被拒绝，避免任意文件读取/写入。</li>
 * </ol>
 */
public final class SessionIds {

    /** 合法会话号：32 位小写十六进制（即 UUID 去横线） */
    private static final java.util.regex.Pattern SID_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-f]{32}$");

    private SessionIds() {
        // 工具类
    }

    /**
     * 生成一个新的 sessionId。
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase();
    }

    /**
     * 校验会话号格式是否合法（不合法一律拒绝，用于防路径穿越）。
     */
    public static boolean isValid(String sessionId) {
        return sessionId != null && SID_PATTERN.matcher(sessionId).matches();
    }

    /**
     * 格式非法时抛出的异常。
     */
    public static void requireValid(String sessionId) {
        if (!isValid(sessionId)) {
            throw new SessionForbiddenException("会话号格式非法");
        }
    }
}
