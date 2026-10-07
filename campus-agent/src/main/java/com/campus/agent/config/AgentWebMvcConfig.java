package com.campus.agent.config;

import com.campus.agent.auth.AgentJwtTool;
import com.campus.agent.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Web MVC 配置：注册 {@link AuthInterceptor}。
 * <p>
 * 放行路径（无需登录）：
 * <ul>
 *     <li>{@code /agent/sessions}（POST 创建会话本身也要鉴权，故仅放行查询类）</li>
 *     <li>健康检查 {@code /agent/health}、错误页与静态资源</li>
 * </ul>
 * 真正的业务接口（发消息、查历史、删会话）全部要求登录。
 */
@Configuration
@RequiredArgsConstructor
public class AgentWebMvcConfig implements WebMvcConfigurer {

    private final AgentJwtTool jwtTool;

    /** 无需鉴权的路径前缀 */
    private final List<String> excludePaths = new ArrayList<>(List.of(
            "/agent/health",
            "/error",
            "/favicon.ico",
            "/actuator/**"
    ));

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(jwtTool))
                .addPathPatterns("/agent/**")
                .excludePathPatterns(excludePaths);
    }
}
