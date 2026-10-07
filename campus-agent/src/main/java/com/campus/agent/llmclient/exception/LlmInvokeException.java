package com.campus.agent.llmclient.exception;

/**
 * LLM 调用统一异常。
 * <p>
 * 用于包装：网络异常、连接/读取超时、4xx/5xx HTTP 错误、响应解析错误等，
 * 避免底层 RestTemplate / Jackson 异常直接泄漏给上层。
 */
public class LlmInvokeException extends RuntimeException {

    /** HTTP 状态码；非 HTTP 类异常（如超时、解析失败）为 null */
    private final Integer statusCode;

    public LlmInvokeException(String message) {
        super(message);
        this.statusCode = null;
    }

    public LlmInvokeException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public LlmInvokeException(String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
