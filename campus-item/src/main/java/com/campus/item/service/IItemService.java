package com.campus.item.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.api.dto.ItemDTO;
import com.campus.api.dto.OrderDetailDTO;
import com.campus.common.domain.PageDTO;
import com.campus.item.domain.po.Item;
import com.campus.item.domain.po.ItemDoc;
import com.campus.item.domain.query.ItemPageQuery;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IItemService extends IService<Item> {

    void deductStock(List<OrderDetailDTO> items);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    PageDTO<ItemDoc> search(ItemPageQuery query);

    void updateEsStock(Long itemId);
}
