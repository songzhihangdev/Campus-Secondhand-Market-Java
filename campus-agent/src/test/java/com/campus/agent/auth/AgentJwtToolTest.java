package com.campus.agent.auth;

import com.campus.agent.config.AgentJwtProperties;
import com.campus.agent.config.AgentSecurityConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.security.KeyPair;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent JWT 签发与解析测试。
 * <p>
 * 使用<b>真实 jks 密钥库</b>（classpath 的 hmall.jks），验证：
 * 密钥能正确加载、token 能签发并解析出 userId、过期/伪造 token 被拒绝。
 * <p>
 * 这同时验证了"Agent 与商城共用同一把密钥"这一 JWT 透传方案的前提。
 */
class AgentJwtToolTest {

    private static AgentJwtTool jwtTool;

    private static KeyPair keyPair;

    @BeforeAll
    static void setUp() {
        AgentJwtProperties properties = new AgentJwtProperties();
        properties.setLocation(new ClassPathResource("hmall.jks"));
        properties.setAlias("hmall");
        properties.setPassword("hmall123");
        properties.setTokenTTL(Duration.ofMinutes(30));

        // 走真实的配置类加载密钥，确保与商城侧加载方式一致
        keyPair = new AgentSecurityConfig().keyPair(properties);
        assertNotNull(keyPair);
        jwtTool = new AgentJwtTool(keyPair, properties);
    }

    @Test
    @DisplayName("能正确从 jks 加载出 RSA 密钥对")
    void keyPair_shouldLoadFromJks() {
        assertNotNull(keyPair.getPrivate());
        assertNotNull(keyPair.getPublic());
        assertEquals("RSA", keyPair.getPublic().getAlgorithm());
    }

    @Test
    @DisplayName("签发的 token 能解析出正确的 userId")
    void createAndParse_shouldReturnUserId() {
        String token = jwtTool.createToken(1001L, Duration.ofMinutes(10));
        assertNotNull(token);
        assertEquals(1001L, jwtTool.parseToken(token));
    }

    @Test
    @DisplayName("带 Bearer 前缀的 token 也能解析")
    void parseToken_shouldAcceptBearerPrefix() {
        String token = jwtTool.createToken(1001L, Duration.ofMinutes(10));
        assertEquals(1001L, jwtTool.parseToken("Bearer " + token));
    }

    @Test
    @DisplayName("不同用户的 token 解析出各自的 userId")
    void parseToken_shouldDistinguishUsers() {
        assertEquals(1001L, jwtTool.parseToken(jwtTool.createToken(1001L, Duration.ofMinutes(5))));
        assertEquals(2002L, jwtTool.parseToken(jwtTool.createToken(2002L, Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("过期 token 被拒绝")
    void expiredToken_shouldBeRejected() {
        // 签发一个已经过期 1 秒的 token
        String token = jwtTool.createToken(1001L, Duration.ofSeconds(-1));
        AgentAuthException e = assertThrows(AgentAuthException.class, () -> jwtTool.parseToken(token));
        assertTrue(e.getMessage().contains("过期") || e.getMessage().contains("无效"));
    }

    @Test
    @DisplayName("空 token / null 被拒绝")
    void emptyToken_shouldBeRejected() {
        assertThrows(AgentAuthException.class, () -> jwtTool.parseToken(null));
        assertThrows(AgentAuthException.class, () -> jwtTool.parseToken(""));
        assertThrows(AgentAuthException.class, () -> jwtTool.parseToken("   "));
    }

    @Test
    @DisplayName("被篡改的 token 被拒绝（验签失败）")
    void tamperedToken_shouldBeRejected() {
        String token = jwtTool.createToken(1001L, Duration.ofMinutes(10));
        // 改动签名部分
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(AgentAuthException.class, () -> jwtTool.parseToken(tampered));
    }

    @Test
    @DisplayName("用其它密钥签发的 token 无法通过本模块验签")
    void foreignToken_shouldBeRejected() {
        // 造一把无关的 RSA 密钥对，用它签发 token
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair foreign = generator.generateKeyPair();

            AgentJwtProperties p = new AgentJwtProperties();
            p.setLocation(new ClassPathResource("hmall.jks"));
            p.setAlias("hmall");
            p.setPassword("hmall123");

            // 用外部密钥构造的 JwtTool 签发，本模块验签必然失败
            AgentJwtTool foreignTool = new AgentJwtTool(foreign, p);
            String foreignToken = foreignTool.createToken(1001L, Duration.ofMinutes(10));

            assertThrows(AgentAuthException.class, () -> jwtTool.parseToken(foreignToken));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
