package com.campus.agent.context;

/**
 * 商城会话上下文持有器：基于 {@link ThreadLocal} 保存当前会话 token。
 * <p>
 * 仅开发阶段使用；线程处理结束后务必调用 {@link #clear()} 防止内存泄漏与串号。
 * 后续可平滑替换为基于 Redis 的分布式会话，而调用方（执行器）无需改动。
 */
public final class MallContextHolder {

    /** 当前线程绑定的登录 token */
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

    private MallContextHolder() {
        // 工具类，禁止实例化
    }

    /** 获取当前会话 token，未登录时返回 null */
    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    /** 设置当前会话 token（例如登录成功后保存） */
    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    /** 清除当前线程上下文，请求/任务结束时必须调用 */
    public static void clear() {
        TOKEN_HOLDER.remove();
    }
}
