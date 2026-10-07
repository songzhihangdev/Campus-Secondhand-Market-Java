package com.campus.agent.context;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 请求级用户上下文：保存当前请求解析出的登录用户 id 与<b>原始 JWT 字符串</b>。
 * <p>
 * 为什么除 userId 外还要保存 JWT 原文：执行器调用商城接口时需要把
 * {@code Authorization: Bearer {jwt}} 原样透传给商城（商城用自己的 LoginInterceptor 验签）。
 * 这样 Agent 侧就不需要再维护"token 缓存"这套逻辑——身份始终来自本次请求的凭证。
 * <p>
 * 基于 ThreadLocal，请求结束时必须调用 {@link #clear()}（由 AuthInterceptor 的 afterCompletion 保证），
 * 否则线程池复用会导致串号。
 */
public final class AgentUserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private static final ThreadLocal<String> JWT = new ThreadLocal<>();

    private AgentUserContext() {
        // 工具类，禁止实例化
    }

    public static void setUser(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUser() {
        return USER_ID.get();
    }

    public static void setJwt(String jwt) {
        JWT.set(jwt);
    }

    /**
     * 获取当前请求的 JWT 原文（含 "Bearer " 前缀，透传给商城时直接可用）。
     */
    public static String getJwt() {
        return JWT.get();
    }

    public static void clear() {
        USER_ID.remove();
        JWT.remove();
    }

    /**
     * 便捷方法：把 JWT 写入 {@link MallContextHolder}，供执行器拼接请求头。
     */
    public static void syncJwtToMallContext() {
        MallContextHolder.setToken(getJwt());
    }

    /**
     * 仅用于测试/调试：查看当前上下文的键值对（不含敏感值）。
     */
    public static Map<String, Object> debugView() {
        Map<String, Object> view = new HashMap<>(4);
        view.put("userId", getUser());
        view.put("hasJwt", getJwt() != null);
        return view;
    }
}
