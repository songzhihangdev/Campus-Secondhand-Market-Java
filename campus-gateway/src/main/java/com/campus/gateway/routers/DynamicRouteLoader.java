package com.campus.gateway.routers;

import cn.hutool.json.JSONUtil;
import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@Component
@Slf4j
@RequiredArgsConstructor
public class DynamicRouteLoader implements CommandLineRunner {

    private final NacosConfigManager nacosConfigManager;
    private final RouteDefinitionWriter routeDefinitionWriter;
    private final ApplicationEventPublisher applicationEventPublisher;

    private final String dataId = "gateway-routes.json";

    private final String group = "DEFAULT_GROUP";
    private final List<String> routeIds = new ArrayList<>();

    @Override
    public void run(String... args) throws Exception {
        initRouteConfigLister();
    }

    public void initRouteConfigLister() throws NacosException {
        //当项目启动是，先拉取一次配置，并添加配置监听器
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(dataId, group, 5000, new Listener() {

                    @Override
                    public Executor getExecutor() {
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        log.info("接收到配置变更：{}", configInfo);
                        updateRouteConfig(configInfo);
                    }
                });
        //第一次读取到配置，也需要更新路由表
        updateRouteConfig(configInfo);
    }

    private void updateRouteConfig(String confidInfo) {
        log.debug("接收到配置变更：{}", confidInfo);
        //解析配置文件，转换为RouteDefinition对象
        List<RouteDefinition> routeDefinitions = JSONUtil.toList(confidInfo, RouteDefinition.class);
        //删除原有路由表
        for(String routeId:routeIds){
            routeDefinitionWriter.delete(Mono.just(routeId)).subscribe();
        }
        routeIds.clear();
        //更新路由表
        for(RouteDefinition routeDefinition:routeDefinitions){
            routeDefinitionWriter.save(Mono.just(routeDefinition)).subscribe();
            //记录路由id,方便后续删除
            routeIds.add(routeDefinition.getId());

        }
        // 保存完成后发布事件，让网关重建路由链，动态路由才能生效
        applicationEventPublisher.publishEvent(new RefreshRoutesEvent(this));



    }
}
