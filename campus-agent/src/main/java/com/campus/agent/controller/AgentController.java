package com.campus.agent.controller;

import com.campus.agent.auth.AgentAuthException;
import com.campus.agent.context.AgentUserContext;
import com.campus.agent.scheduler.AgentResult;
import com.campus.agent.scheduler.ReActAgentScheduler;
import com.campus.agent.session.ChatMessageRecord;
import com.campus.agent.session.SessionManager;
import com.campus.agent.session.SessionSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 会话与对话接口（面向前端）。
 * <p>
 * <b>鉴权</b>：所有接口都必须携带 {@code authorization: Bearer {jwt}}，
 * 由 {@link com.campus.agent.auth.AuthInterceptor} 统一校验并把 userId 写入
 * {@link AgentUserContext}；本控制器只从上下文取 userId，<b>绝不接受前端传的用户标识</b>。
 * <p>
 * <b>接口清单</b>：
 * <ul>
 *     <li>{@code POST   /agent/sessions}                新建会话（sessionId 服务端生成）</li>
 *     <li>{@code GET    /agent/sessions}                会话列表</li>
 *     <li>{@code GET    /agent/sessions/{sid}/history}  某会话历史消息</li>
 *     <li>{@code POST   /agent/sessions/{sid}/messages}  发消息，触发 ReAct</li>
 *     <li>{@code DELETE /agent/sessions/{sid}}          删除会话</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent/sessions")
@RequiredArgsConstructor
public class AgentController {

    private final ReActAgentScheduler scheduler;

    private final SessionManager sessionManager;

    /**
     * 新建会话：sessionId 由服务端生成并返回，前端只需保存它。
     */
    @PostMapping
    public Map<String, Object> createSession() {
        Long userId = requireUserId();
        String sessionId = sessionManager.createSession(userId);
        return Map.of("sessionId", sessionId);
    }

    /**
     * 会话列表：只返回当前登录用户的会话（按更新时间倒序）。
     */
    @GetMapping
    public List<SessionSummary> listSessions() {
        return sessionManager.listSessions(requireUserId());
    }

    /**
     * 读取某会话的完整聊天记录，供前端渲染历史消息。
     */
    @GetMapping("/{sessionId}/history")
    public List<ChatMessageRecord> history(@PathVariable String sessionId) {
        return sessionManager.history(sessionId, requireUserId());
    }

    /**
     * 发一条用户消息，触发一次完整的 ReAct 推理-行动-观察循环。
     */
    @PostMapping("/{sessionId}/messages")
    public AgentResult sendMessage(@PathVariable String sessionId,
                                   @RequestBody Map<String, String> body) {
        Long userId = requireUserId();
        String query = body == null ? null : body.get("content");
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        return scheduler.run(sessionId, userId, query);
    }

    /**
     * 删除会话（同时清除内存与落盘文件）。
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Boolean> deleteSession(@PathVariable String sessionId) {
        sessionManager.deleteSession(sessionId, requireUserId());
        return Map.of("deleted", true);
    }

    private Long requireUserId() {
        Long userId = AgentUserContext.getUser();
        if (userId == null) {
            // 理论上被拦截器拦住，走到这里说明配置有误，直接拒绝
            throw new AgentAuthException("未登录");
        }
        return userId;
    }

    /**
     * 健康检查（无需鉴权，已在拦截器中放行）。
     * <p>
     * 注意：必须放在类级 {@code @RequestMapping("/agent/sessions")} 之外，
     * 否则实际路径会变成 /agent/sessions/health。
     */
    @RestController
    public static class HealthController {

        @GetMapping("/agent/health")
        public Map<String, Object> health() {
            return Map.of("status", "UP");
        }
    }
}
