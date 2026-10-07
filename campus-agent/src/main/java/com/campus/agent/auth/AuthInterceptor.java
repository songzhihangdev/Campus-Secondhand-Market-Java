package com.campus.agent.auth;

import com.campus.agent.context.AgentUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录拦截器：校验 {@code authorization} 请求头，解析出 userId 与 JWT 原文并写入 {@link AgentUserContext}。
 * <p>
 * 请求结束时 <b>必须清理 ThreadLocal</b>，否则 Tomcat 线程池复用会导致会话串号/越权。
 * <p>
 * 同时把 JWT 同步到 {@link com.campus.agent.context.MallContextHolder}，
 * 使执行器（MallApiExecutor）在调用需鉴权的微服务接口时能自动带上 {@code Authorization} 头——这就是"JWT 透传"。
 */
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AgentJwtTool jwtTool;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("authorization");
        Long userId = jwtTool.parseToken(token);

        // 1. 记录身份
        AgentUserContext.setUser(userId);
        // 2. 记录 JWT 原文（去掉 Bearer 前缀，供执行器 setBearerAuth 使用）
        AgentUserContext.setJwt(normalize(token));
        // 3. 同步给执行器读取
        AgentUserContext.syncJwtToMallContext();

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 无论成功失败都要清理，防止线程池串号
        AgentUserContext.clear();
        com.campus.agent.context.MallContextHolder.clear();
    }

    private String normalize(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).trim();
        }
        return value;
    }

    /**
     * 供 WebMvcConfigurer 使用的 HTTP 状态码常量。
     */
    public static final int UNAUTHORIZED = HttpStatus.UNAUTHORIZED.value();

    /**
     * 越权访问（会话不属于当前用户）。
     */
    public static final int FORBIDDEN = HttpStatus.FORBIDDEN.value();
}
