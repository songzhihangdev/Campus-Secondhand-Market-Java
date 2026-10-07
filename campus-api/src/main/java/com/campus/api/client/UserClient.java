package com.campus.api.client;

import com.campus.api.dto.AddressDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient("campus-user")
public interface UserClient {

    @PutMapping("/users/money/deduct")
    void deductMoney(@RequestParam("pw") String pw, @RequestParam("amount") Integer amount);

    /**
     * 按id查询一条收货地址。
     *
     * <p>供 campus-trade 在<b>下单时</b>抓取地址快照写入 order_logistics ——
     * 订单是法律凭证，不能在展示时才实时查询（地址随时可能被用户改动或删除）。
     *
     * <p><b>归属校验在服务端</b>：user 服务的 AddressController 会比对
     * 当前登录用户，传他人的 addressId 会返回 400。
     * JWT 由 {@code DefaultFeignConfig} 的 RequestInterceptor 自动透传。
     *
     * @param id 地址id
     * @return 地址详情；不存在或非本人时服务端抛 400，Feign 会收到异常
     */
    @GetMapping("/addresses/{id}")
    AddressDTO findAddressById(@PathVariable("id") Long id);
}