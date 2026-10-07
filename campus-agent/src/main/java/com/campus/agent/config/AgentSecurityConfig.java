package com.campus.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 安全相关 Bean 配置：加载 jks 密钥对，供 {@link com.campus.agent.auth.AgentJwtTool} 构造 RS256 签名器。
 * <p>
 * <b>为什么不直接依赖 spring-security-rsa 的 KeyStoreKeyFactory？</b><br>
 * 该类所在的 {@code spring-security-rsa:1.0.9.RELEASE} 会传递引入
 * {@code spring-security-crypto:4.2.x}，而 Spring Boot 2.7 的 BOM 会把 crypto 提升到 5.7.x，
 * 导致该包在编译期不可见（包结构在 5.x 已调整）。为避免这处版本冲突，
 * 这里用 JDK 原生的 {@link KeyStore} API 自行读取密钥对，<b>零额外依赖</b>，行为与微服务侧完全一致。
 * <p>
 * 密钥库与商城侧（campus-user / campus-trade 等）共用同一个 {@code hmall.jks}，
 * 因此本模块签发/解析的 token 与商城完全互通——这是"JWT 透传"成立的前提。
 */
@Configuration
@EnableConfigurationProperties(AgentJwtProperties.class)
public class AgentSecurityConfig {

    /**
     * 从 jks 读取 RSA 密钥对（私钥 + 公钥）。
     * <p>
     * jks 中的条目由私钥和它的 X.509 证书两条组成，因此需要分别取出再组装 KeyPair。
     */
    @Bean
    public KeyPair keyPair(AgentJwtProperties properties) {
        char[] password = properties.getPassword().toCharArray();
        try (InputStream in = properties.getLocation().getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            keyStore.load(in, password);

            // 取别名为私钥条目
            KeyStore.PrivateKeyEntry entry =
                    (KeyStore.PrivateKeyEntry) keyStore.getEntry(properties.getAlias(), new KeyStore.PasswordProtection(password));
            if (entry == null) {
                throw new IllegalStateException("jks 中不存在别名为 " + properties.getAlias() + " 的条目");
            }

            PrivateKey privateKey = entry.getPrivateKey();

            // 从同一条目的证书链中取 X.509 证书，反推出公钥
            Certificate certificate = entry.getCertificate();
            if (certificate == null) {
                throw new IllegalStateException("jks 条目 " + properties.getAlias() + " 缺少证书，无法提取公钥");
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate x509 = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(certificate.getEncoded()));
            java.security.PublicKey publicKey = x509.getPublicKey();

            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException("加载 jks 密钥库失败，请检查 agent.jwt.location/alias/password 配置", e);
        }
    }
}
