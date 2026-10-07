package com.campus.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.api.client.ItemClient;
import com.campus.api.dto.ItemDTO;
import com.campus.cart.config.CartProperties;
import com.campus.common.exception.BizIllegalException;
import com.campus.common.utils.BeanUtils;
import com.campus.common.utils.CollUtils;
import com.campus.common.utils.UserContext;
import com.campus.cart.domain.dto.CartFormDTO;
import com.campus.cart.domain.po.Cart;
import com.campus.cart.domain.vo.CartVO;
import com.campus.cart.mapper.CartMapper;
import com.campus.cart.service.ICartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    //用final修饰可以实现自动注入（配合@RequireArgsConstructor）
    //private final RestTemplate restTemplate;
    //private final DiscoveryClient discoveryClient;
    private final CartProperties cartProperties;

    private final ItemClient itemClient;


    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();

        // 2.判断是否已经存在
        if(checkItemExists(cartFormDTO.getItemId(), userId)){
            // 2.1.存在，则更新数量
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        // 2.2.不存在，判断是否超过购物车数量
        checkCartsFull(userId);

        // 3.新增购物车条目
        // 3.1.转换PO
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        // 3.2.保存当前用户
        cart.setUserId(userId);
        // 3.3.保存到数据库
        save(cart);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId, UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        // 2.转换VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        // 3.处理VO中的商品信息
        handleCartItems(vos);

        // 4.返回
        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        // 1.获取商品id
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
//        // 2.查询商品
//        //2.1根据服务名称获取服务的实例列表
//        List<ServiceInstance> instances = discoveryClient.getInstances("item-service");
//        if(CollUtil.isEmpty(instances)){
//            return ;
//        }
//        //2.2手写负载均衡实例，从实例里选择一个实例
//        ServiceInstance instance = instances.get(RandomUtil.randomInt(instances.size()));
//
//        //List<ItemDTO> items = itemService.queryItemByIds(itemIds);
//        //2.1利用restTemplate发起http请求，得到http响应
//        ResponseEntity<List<ItemDTO>> response = restTemplate.exchange(
//                instance.getUri()+"/items?ids={ids}",
//                HttpMethod.GET,
//                null,
//                new ParameterizedTypeReference<List<ItemDTO>>() {},
//                Map.of("ids", CollUtil.join(itemIds, ",")));
//        //2.2解析响应数据
//        if(!response.getStatusCode().is2xxSuccessful()){
//            return;
//        }
//        List<ItemDTO> items = response.getBody();
        List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 3.转为 id 到 item的map
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        // 4.写入vo
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    public void removeByItemIds(Collection<Long> itemIds) {
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, UserContext.getUser())
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        Long count = lambdaQuery().eq(Cart::getUserId, userId).count();
        // 提示里的数字取实际配置值，不要硬编码 —— 改配置后提示会不一致
        Integer max = cartProperties.getMaxItems();
        if (count >= max) {
            throw new BizIllegalException(StrUtil.format("意向单最多容纳{}种物品", max));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        Long count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }
}
