package com.campus.api.client;

import com.campus.api.dto.ItemDTO;
import com.campus.api.dto.OrderDetailDTO;
import io.swagger.annotations.ApiOperation;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

@FeignClient(value = "campus-item")
public interface ItemClient {

    @GetMapping("/items")
    List<ItemDTO> queryItemsByIds(@RequestParam("ids") Collection<Long> ids);

    @PutMapping("/items/stock/deduct")
    void deductStock(@RequestBody List<OrderDetailDTO> items);

    @ApiOperation("更新商品")
    @PutMapping("/items")
    public void updateItem(@RequestBody ItemDTO item);

    @ApiOperation("根据id查询商品")
    @GetMapping("/items/{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id);

    /**
     * 将物品标记为已售（下架）。
     *
     * <p>供 campus-trade 的"确认收货"调用 —— 二手商品确认卖出后必须下架，
     * 否则还能被再次下单购买。
     *
     * <p>不复用 {@code updateItem}：那个接口会做归属校验（只允许卖家改），
     * 且会把 status 强制置 null，压根传不了状态。
     */
    @PutMapping("/items/sold/{id}")
    void markAsSold(@PathVariable("id") Long id);

}
