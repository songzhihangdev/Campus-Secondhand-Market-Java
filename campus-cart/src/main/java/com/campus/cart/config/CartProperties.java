package com.campus.cart.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hm.cart")
public class CartProperties {
    /**
     * 意向单最多容纳的物品种类数。
     *
     * <p><b>必须有默认值</b>：原来这里是 null，而 {@code checkCartsFull} 里
     * {@code count >= cartProperties.getMaxItems()} 会自动拆箱，
     * 遇到 null 直接抛 NullPointerException → 加入意向单一律 500。
     *
     * <p>之所以会 null：原配置在 Nacos 的 {@code cart-service.yaml} 里，
     * 而服务注册名已改为 {@code campus-cart}，那份配置<b>不再被加载</b>。
     * 因此不能依赖外部配置必须存在——默认值才是可靠做法。
     */
    private Integer maxItems = 10;
}
