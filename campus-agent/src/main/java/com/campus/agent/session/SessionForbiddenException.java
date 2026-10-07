package com.campus.agent.session;

/**
 * 会话访问越权异常（HTTP 403）。
 * <p>
 * 与 {@link com.campus.agent.auth.AgentAuthException}(401) 区分：
 * 401 表示"没登录/登录无效"，403 表示"登录了但无权访问该资源"。
 */
public class SessionForbiddenException extends RuntimeException {

    public SessionForbiddenException(String message) {
        super(message);
    }
}
