package com.campus.gateway.filter;


import com.campus.common.exception.UnauthorizedException;
import com.campus.gateway.config.AuthProperties;
import com.campus.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final AuthProperties authProperties;
    private final JwtTool jwtTool;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        //获取request
        ServerHttpRequest request = exchange.getRequest();
        //判断是否需要拦截
        if(isExcludePath(request.getPath().toString()))
        {
            //放行
            return chain.filter(exchange);
        }
        //获取token
        List<String> hearders = request.getHeaders().get("Authorization");
        if(hearders == null || hearders.isEmpty()){
            // 无凭证一律拒绝，不能放行——否则去掉 Authorization 头即可绕过鉴权，
            // 校园二手场景下会导致任何人可发布/改删他人闲置
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        String token = hearders.get(0);
        // 兼容标准 “Bearer <jwt>” 与裸 jwt 两种写法，剥离前缀后再校验
        if (token.toLowerCase().startsWith("bearer ")) {
            token = token.substring(7).trim();
        }
        if (token.isEmpty()) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        //检验并解析token
        Long userId = null;
        try {
            userId = jwtTool.parseToken(token);
        }catch (UnauthorizedException e){
            //拦截，设置响应状态码为401
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return response.setComplete();
        }
        //传递用户信息
        String userInfo = userId.toString();
        ServerWebExchange swe = exchange.mutate()
                .request(builder -> builder.header("userInfo", userInfo))
                .build();
        return chain.filter(swe);
    }

    private boolean isExcludePath(String path) {
        for(String pathPattern:authProperties.getExcludePaths()){
            if(antPathMatcher.match(pathPattern,path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 2;
    }
}
