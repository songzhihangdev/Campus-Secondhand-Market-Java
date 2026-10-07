package com.campus.agent.controller;

import com.campus.agent.auth.AgentAuthException;
import com.campus.agent.session.SessionForbiddenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：把内部异常翻译成稳定的 HTTP 状态与 JSON 结构。
 * <p>
 * 与执行器内部"把商城异常包装成 observation 文本"的策略<b>刻意相反</b>：
 * 身份/权限问题不允许降级为文本，必须明确返回 401/403，否则前端无法区分
 * "接口坏了"和"你没登录/你没权限"。
 */
@Slf4j
@RestControllerAdvice
public class AgentExceptionHandler {

    /**
     * 未登录 / token 无效 / token 过期 → 401
     */
    @ExceptionHandler(AgentAuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AgentAuthException e) {
        log.warn("鉴权失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", 401, "message", e.getMessage()));
    }

    /**
     * 会话越权 / 非法会话号 / 非法路径 → 403
     */
    @ExceptionHandler(SessionForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(SessionForbiddenException e) {
        log.warn("访问被拒绝：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", 403, "message", e.getMessage()));
    }

    /**
     * 兜底：内部错误统一 500，不向客户端暴露堆栈细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("请求处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", "服务内部错误：" + e.getMessage()));
    }
}
