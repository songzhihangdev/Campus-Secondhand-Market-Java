package com.campus.item.controller;


import com.campus.api.dto.ItemDTO;
import com.campus.common.domain.PageDTO;
import com.campus.common.utils.BeanUtils;
import com.campus.item.domain.po.ItemDoc;
import com.campus.item.domain.query.ItemPageQuery;
import com.campus.item.service.IItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Api(tags = "搜索相关接口")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final IItemService itemService;

    @ApiOperation("搜索商品")
    @GetMapping("/list")
    public PageDTO<ItemDTO> search(ItemPageQuery query) {
        // 基于Elasticsearch搜索
        PageDTO<ItemDoc> result = itemService.search(query);
        // 封装并返回
        List<ItemDTO> list = BeanUtils.copyList(result.getList(), ItemDTO.class);
        return new PageDTO<>(result.getTotal(), result.getPages(), list);
    }
}
