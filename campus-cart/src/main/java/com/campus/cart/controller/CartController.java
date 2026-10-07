package com.campus.cart.controller;

import com.campus.cart.domain.dto.CartFormDTO;
import com.campus.cart.domain.po.Cart;
import com.campus.cart.domain.vo.CartVO;
import com.campus.cart.service.ICartService;
import com.campus.cart.utils.CartOwnershipChecker;
import com.campus.common.exception.BadRequestException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Api(tags = "意向单相关接口")
@RestController
@RequestMapping("/carts")
@RequiredArgsConstructor
public class CartController {
    private final ICartService cartService;

    @ApiOperation("加入意向单")
    @PostMapping
    public void addItem2Cart(@Valid @RequestBody CartFormDTO cartFormDTO) {
        cartService.addItem2Cart(cartFormDTO);
    }

    @ApiOperation("修改意向单（数量/状态）")
    @PutMapping
    public void updateCart(@RequestBody Cart cart) {
        if (cart == null || cart.getId() == null) {
            throw new BadRequestException("意向单条目id不能为空");
        }
        // 先校验归属：否则可改他人条目的数量/状态
        Cart existing = cartService.getById(cart.getId());
        CartOwnershipChecker.checkOwner(existing);

        // 强制把 userId 钉死为当前登录用户，
        // 防止请求体伪造 userId 把条目「转移」到别人名下
        cart.setUserId(CartOwnershipChecker.requireLogin());
        cartService.updateById(cart);
    }

    @ApiOperation("移出意向单")
    @DeleteMapping("{id}")
    public void deleteCartItem(@Param("意向单条目id") @PathVariable("id") Long id) {
        if (id == null || id <= 0) {
            throw new BadRequestException("意向单条目id不合法");
        }
        // 先校验归属：原实现直接 removeById(id)，存在水平越权，
        // 任何人拿到条目 id 都能删掉别人的意向单条目（已实测复现）
        Cart existing = cartService.getById(id);
        CartOwnershipChecker.checkOwner(existing);

        cartService.removeById(id);
    }

    @ApiOperation("查询我的意向单")
    @GetMapping
    public List<CartVO> queryMyCarts() {
        return cartService.queryMyCarts();
    }

    @ApiOperation("批量移出意向单")
    @ApiImplicitParam(name = "ids", value = "物品id集合", paramType = "query")
    @DeleteMapping
    public void deleteCartItemByIds(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 归属校验交给 service：removeByItemIds 内部已按当前 userId 过滤，
        // 即使传入他人的 itemId 也删不掉（安全）
        cartService.removeByItemIds(ids);
    }
}
