package com.campus.trade.controller;

import lombok.extern.slf4j.Slf4j;
import com.campus.trade.domain.po.OrderLogistics;
import com.campus.trade.domain.vo.OrderAddressVO;
import com.campus.trade.service.IOrderLogisticsService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campus.common.exception.BadRequestException;
import com.campus.common.utils.BeanUtils;
import com.campus.trade.domain.dto.OrderFormDTO;
import com.campus.trade.domain.po.Order;
import com.campus.trade.domain.po.OrderDetail;
import com.campus.trade.domain.vo.OrderDetailVO;
import com.campus.trade.domain.vo.OrderVO;
import com.campus.trade.service.IOrderDetailService;
import com.campus.trade.service.IOrderService;
import com.campus.trade.utils.OrderOwnershipChecker;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Api(tags = "订单管理接口")
@Slf4j
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final IOrderService orderService;
    private final IOrderDetailService detailService;
    private final IOrderLogisticsService orderLogisticsService;

    @ApiOperation("根据id查询订单")
    @GetMapping("{id}")
    public OrderVO queryOrderById(@Param("订单id") @PathVariable("id") Long orderId) {
        // 必须先校验归属，否则任何人拿到 orderId 都能查别人的订单
        Order order = orderService.getById(orderId);
        OrderOwnershipChecker.checkOwner(order);
        return fillDetails(BeanUtils.copyBean(order, OrderVO.class), orderId);
    }

    @ApiOperation("查询我的订单列表")
    @GetMapping
    public List<OrderVO> queryMyOrders() {
        // userId 只从登录上下文取，绝不接受前端传参
        Long userId = OrderOwnershipChecker.requireLogin();
        List<Order> orders = orderService.list(
                new QueryWrapper<Order>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time"));
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        // 用 for 循环而非 stream 双 map：第二个 lambda 里拿不到第一个的入参 o，
        // 且泛型推断在 chain 中容易失控，可读性也更差。
        // 订单列表必须能看到「买了什么」——二手订单常只买 1~2 件，
        // 只给金额和状态用户无法确认订单内容。
        List<OrderVO> result = new ArrayList<>(orders.size());
        for (Order o : orders) {
            OrderVO vo = BeanUtils.copyBean(o, OrderVO.class);
            result.add(fillDetails(vo, o.getId()));
        }
        return result;
    }

    @ApiOperation("创建订单")
    @PostMapping
    public Long createOrder(@RequestBody OrderFormDTO orderFormDTO) {
        return orderService.createOrder(orderFormDTO);
    }

    @ApiOperation("标记订单已支付")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}")
    public void markOrderPaySuccess(@PathVariable("orderId") Long orderId) {
        Order order = orderService.getById(orderId);
        OrderOwnershipChecker.checkOwner(order);
        orderService.markOrderPaySuccess(orderId);
    }

    @ApiOperation("确认收货（交易完成）")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}/confirm")
    public void confirmReceipt(@PathVariable("orderId") Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BadRequestException("订单id不合法");
        }
        // 归属校验与状态校验都在 Service 内（confirmReceipt）
        orderService.confirmReceipt(orderId, OrderOwnershipChecker.requireLogin());
    }

    @ApiOperation("取消订单")
    @ApiImplicitParam(name = "orderId", value = "订单id", paramType = "path")
    @PutMapping("/{orderId}/cancel")
    public void cancelOrder(@PathVariable("orderId") Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new BadRequestException("订单id不合法");
        }
        // 归属校验 + 状态校验全部下沉到 Service（cancelOrderByUser）。
        //
        // 为什么不在 Controller 做：Service 是唯一入口，
        // 将来 Agent 技能、MQ 监听器或其他调用方走 Service 也能受同样的约束。
        //
        // 原来的写法 if (status != 1 && status != 2) 等于放行了 status=2（已支付），
        // 付了钱还能取消 → 关单会加回库存 → 同商品可被反复购买，是资损型 bug。
        orderService.cancelOrderByUser(orderId, OrderOwnershipChecker.requireLogin());
    }

    /**
     * 填充订单明细与名称摘要。
     *
     * <p>明细查询失败不应导致整个订单接口失败，因此吞掉异常只记日志——
     * 极端情况下用户看到没有明细的订单，远好过整页报错。
     */
    private OrderVO fillDetails(OrderVO vo, Long orderId) {
        if (vo == null || orderId == null) {
            return vo;
        }
        // 填收货信息快照（下单时写入 order_logistics 的那份地址）
        fillAddress(vo, orderId);
        try {
            List<OrderDetail> details = detailService.list(
                    new QueryWrapper<OrderDetail>().eq("order_id", orderId));
            if (details == null || details.isEmpty()) {
                return vo;
            }
            List<OrderDetailVO> detailVOs = details.stream()
                    .map(d -> BeanUtils.copyBean(d, OrderDetailVO.class))
                    .collect(Collectors.toList());
            vo.setDetails(detailVOs);
            vo.setItemSummary(buildSummary(detailVOs));
        } catch (Exception ignored) {
            // 明细是锦上添花，查不到不该让订单列表整体失败
        }
        return vo;
    }

    /**
     * 填充收货信息快照。
     *
     * <p>读的是 {@code order_logistics} 里下单时存下的地址<b>快照</b>，
     * 而非实时调 user 服务 —— 地址随时可能被用户改动或删除，
     * 订单要能永久展示当时约定的收货信息。
     *
     * <p>老订单（下单时还没写快照）查不到记录，address 保持 null，
     * 前端据此隐藏收货信息区块，不报错。
     */
    private void fillAddress(OrderVO vo, Long orderId) {
        try {
            OrderLogistics logistics = orderLogisticsService.getByOrderId(orderId);
            if (logistics == null) {
                return;
            }
            OrderAddressVO addr = new OrderAddressVO();
            addr.setContact(logistics.getContact());
            addr.setMobile(logistics.getMobile());
            addr.setProvince(logistics.getProvince());
            addr.setCity(logistics.getCity());
            addr.setTown(logistics.getTown());
            addr.setStreet(logistics.getStreet());
            addr.setLogisticsNumber(logistics.getLogisticsNumber());
            addr.setLogisticsCompany(logistics.getLogisticsCompany());
            vo.setAddress(addr);
        } catch (Exception e) {
            // 地址是补充信息，查不到不该让订单列表整体失败
            log.warn("填充订单收货信息失败 orderId={}, 原因={}", orderId, e.getMessage());
        }
    }

    /**
     * 拼物品名称摘要：最多 2 条，超出显示「等 N 件」。
     */
    private String buildSummary(List<OrderDetailVO> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        String first = details.get(0).getName();
        if (details.size() == 1) {
            return first;
        }
        if (details.size() == 2) {
            return first + "、" + details.get(1).getName();
        }
        return first + " 等 " + details.size() + " 件";
    }
}
