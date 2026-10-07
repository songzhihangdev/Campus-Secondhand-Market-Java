package com.campus.agent.auth;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.campus.agent.config.AgentJwtProperties;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Date;

/**
 * Agent 侧 JWT 工具：签发与解析平台用户 token。
 * <p>
 * 与各微服务（如 {@code campus-trade}）的 JwtTool 使用<b>同一套密钥对与 payload 约定</b>
 * （payload key = "user"，值为 userId），因此：
 * <ul>
 *     <li>前端用 user-service 登录拿到的 token，可以直接访问 Agent 接口；</li>
 *     <li>Agent 把该 token 原样透传给各微服务，微服务侧验签通过，实现"JWT 透传"。</li>
 * </ul>
 */
@Component
public class AgentJwtTool {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JWTSigner jwtSigner;

    private final AgentJwtProperties properties;

    public AgentJwtTool(KeyPair keyPair, AgentJwtProperties properties) {
        this.jwtSigner = JWTSignerUtil.createSigner("rs256", keyPair);
        this.properties = properties;
    }

    /**
     * 为指定用户签发 token（主要供测试与联调用；正常前端应走 user-service 登录）。
     */
    public String createToken(Long userId, Duration ttl) {
        return JWT.create()
                .setPayload("user", userId)
                .setExpiresAt(new Date(System.currentTimeMillis() + ttl.toMillis()))
                .setSigner(jwtSigner)
                .sign();
    }

    public String createToken(Long userId) {
        return createToken(userId, properties.getTokenTTL());
    }

    /**
     * 解析并校验 token，返回其中的 userId。
     *
     * @param token 请求头 {@code authorization} 的值，可带 {@code Bearer } 前缀
     * @return token 中的用户 id
     * @throws AgentAuthException token 缺失/非法/过期/格式错误
     */
    public Long parseToken(String token) {
        String raw = stripBearerPrefix(token);
        if (raw == null || raw.isBlank()) {
            throw new AgentAuthException("未登录");
        }

        JWT jwt;
        try {
            jwt = JWT.of(raw).setSigner(jwtSigner);
        } catch (Exception e) {
            throw new AgentAuthException("无效的token", e);
        }

        // 1. 验签
        if (!jwt.verify()) {
            throw new AgentAuthException("无效的token");
        }

        // 2. 校验过期时间
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            throw new AgentAuthException("token已经过期");
        }

        // 3. 取 payload
        Object userPayload = jwt.getPayload("user");
        if (userPayload == null) {
            throw new AgentAuthException("无效的token");
        }

        // 4. 解析为 Long
        try {
            return Long.valueOf(userPayload.toString());
        } catch (NumberFormatException e) {
            throw new AgentAuthException("无效的token");
        }
    }

    /**
     * 去掉可能存在的 {@code Bearer } 前缀。
     * <p>
     * 注意：微服务侧 {@code LoginInterceptor} 是直接拿整个 header 去 parseToken 的，
     * 说明其签发时并不带前缀；这里做兼容处理，两种形式都能解析。
     */
    private String stripBearerPrefix(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return value.substring(BEARER_PREFIX.length()).trim();
        }
        return value;
    }
}
