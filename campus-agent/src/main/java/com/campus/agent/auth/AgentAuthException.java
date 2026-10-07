package com.campus.agent.auth;

/**
 * Agent 鉴权异常。
 * <p>
 * 与"执行器把后端微服务异常包装成 observation 文本"不同：<b>身份问题不允许降级</b>，
 * 必须立即中断并返回 401，否则就是越权访问。
 */
public class AgentAuthException extends RuntimeException {

    public AgentAuthException(String message) {
        super(message);
    }

    public AgentAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
