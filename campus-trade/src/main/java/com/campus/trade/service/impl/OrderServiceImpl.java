package com.campus.trade.service.impl;

import com.campus.api.constant.ItemStatus;
import com.campus.trade.service.IOrderLogisticsService;
import com.campus.api.dto.AddressDTO;
import com.campus.api.client.UserClient;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.api.client.CartClient;
import com.campus.api.client.ItemClient;
import com.campus.api.dto.ItemDTO;
import com.campus.api.dto.OrderDetailDTO;
import com.campus.common.exception.BadRequestException;
import com.campus.common.utils.UserContext;
import com.campus.trade.constants.MQConstans;
import com.campus.trade.domain.dto.OrderFormDTO;
import com.campus.trade.domain.po.Order;
import com.campus.trade.domain.po.OrderDetail;
import com.campus.trade.mapper.OrderMapper;
import com.campus.trade.service.IOrderDetailService;
import com.campus.trade.enums.OrderStatus;
import com.campus.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final RabbitTemplate rabbitTemplate;

    /** 用于下单时抓取收货地址快照（写入 order_logistics 表） */
    private final UserClient userClient;
    private final IOrderLogisticsService orderLogisticsService;

    @Override
    @Transactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 0.参数校验
        // 原实现直接 orderFormDTO.getDetails().stream()，任何非法输入都是 NPE 500，
        // 前端只能看到"服务器内部异常"。这里补显式校验，把问题变成可读的业务提示。
        if (orderFormDTO == null) {
            throw new BadRequestException("下单参数不能为空");
        }
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        if (CollectionUtils.isEmpty(detailDTOS)) {
            throw new BadRequestException("订单商品不能为空，请先选择要购买的物品");
        }
        // 逐条校验：itemId 与 num 都不能为非法值
        // num<=0 会让 totalFee 算错（0 元下单），也会让扣库存逻辑异常
        for (OrderDetailDTO d : detailDTOS) {
            if (d.getItemId() == null || d.getItemId() <= 0) {
                throw new BadRequestException("订单包含非法的物品id");
            }
            if (d.getNum() == null || d.getNum() <= 0) {
                throw new BadRequestException("物品「" + d.getItemId() + "」的数量必须大于 0");
            }
        }
        
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品
        List<ItemDTO> items = itemClient.queryItemsByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.4.5 校验商品是否可售。
        //
        // 少了这步会出问题：商品下架/已售后仍能被下单购买
        // （实测确认收货把商品标为已售后，依然能再次下单成功）。
        // 二手是独占商品，一件只能卖一次，必须在下单入口拦住。
        for (ItemDTO it : items) {
            if (it.getStatus() != null && it.getStatus() != ItemStatus.ON_SALE) {
                throw new BadRequestException("物品「" + it.getName() + "」已"
                        + (it.getStatus() == ItemStatus.SOLD ? "售出" : "下架")
                        + "，不能再购买");
            }
        }
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);

// 二手场景：addressId 现在会落库了 —— 快照写入 order_logistics 表。
        // 原先 Order 表没有该字段，addressId 传了等于没传（代码里直接忽略）。
        // 现在下单时通过 user 服务抓一份地址快照，之后地址被改/删也不影响订单。
        if (orderFormDTO.getAddressId() != null && orderFormDTO.getAddressId() > 0) {
            try {
                AddressDTO address = userClient.findAddressById(orderFormDTO.getAddressId());
                if (address != null) {
                    orderLogisticsService.saveAddressSnapshot(order.getId(), address);
                }
            } catch (Exception e) {
                // 地址抓取失败不阻断下单：地址是附加信息，商品与价格才是交易主体。
                // 记日志便于事后补录，不把整个下单流程拖垮。
                log.warn("下单时抓取地址快照失败（不影响下单）orderId={}, addressId={}, 原因={}",
                        order.getId(), orderFormDTO.getAddressId(), e.getMessage());
            }
        }

        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.清理购物车商品
        cartClient.deleteCartItemByIds(itemIds);

        // 4.扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }

        //发送延迟消息，检测订单支付状态
        rabbitTemplate.convertAndSend(
                MQConstans.DELAY_EXCHANGE_NAME,
                MQConstans.DELAY_ROUTING_KEY,
                order.getId(),
                message -> {
                    message.getMessageProperties().setDelay(1000*60*30);
                    return message;
                });

        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    /**
     * 用户主动取消订单。
     *
     * <p><b>修复的状态机漏洞</b>：原 {@code cancelOrder} 没有状态校验，
     * 直接 {@code updateById(status=5)}，导致<b>已支付订单也能被取消</b>。
     * 而关单逻辑会把库存加回去 —— 于是出现「已付款 + 库存加回」，
     * 同一件商品可被反复下单购买，属于真实的资损型bug。
     *
     * <p>现在的规则：<b>只有「未支付」的订单能取消</b>。
     * 已支付要走退款流程（当前未实现），而不是直接取消。
     */
    @Override
    public void cancelOrderByUser(Long orderId, Long userId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        // 归属校验：不能取消别人的订单
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BadRequestException("订单不属于当前用户");
        }
        // 状态校验：<b>只有未支付(1)能取消</b>
        // 原 Controller 写的是 status != 1 && status != 2 才拒绝，
        // 相当于放行了 status=2（已支付）—— 付了钱还能取消，
        // 而关单会加回库存 → 同商品可被反复购买，属于资损型bug。
        Integer status = order.getStatus();
        if (status == null || status != OrderStatus.UNPAID.getValue()) {
            throw new BadRequestException("订单当前状态为「"
                    + OrderStatus.textOf(status) + "」，只有待支付订单可以取消");
        }
        doCloseOrder(orderId);
    }

    /**
     * 静默关闭订单（供 MQ 延迟消息调用）。
     *
     * <p>只在「未支付」时关闭。状态不匹配直接返回，<b>不恢复库存</b> ——
     * 避免 MQ 重复投递时对同一订单多次加库存。
     */
    @Override
    public void closeOrderQuietly(Long orderId) {
        Order order = getById(orderId);
        if (order == null) {
            log.warn("延迟关单跳过：订单不存在 orderId={}", orderId);
            return;
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatus.UNPAID.getValue()) {
            // 订单已支付或已关闭，什么都不做（幂等）
            log.info("延迟关单跳过：订单状态非未支付 orderId={}, status={}", orderId, order.getStatus());
            return;
        }
        doCloseOrder(orderId);
    }

    /**
     * 执行关单 + 恢复库存（两个入口共用的实际逻辑）。
     *
     * <p><b>为什么恢复库存的异常要吞掉</b>：
     * 原实现直接让 {@code itemClient.updateItem} 的异常向外抛，
     * 结果是整个事务回滚 → 订单状态从 5 退回 1（未支付），
     * 而 MQ 监听器随之抛异常 → RabbitMQ 默认 requeue → 消息立刻重投
     * → 又看到 status=1 → 又调本方法 → 又失败。<b>无限死循环</b>，
     * 实测把日志刷到上万条，最终消费者被 MQ 判定为 unresponsive 而关闭。
     *
     * <p><b>正确的失败语义</b>：订单该关就关（超时未支付是既定事实），
     * 库存恢复属于"补偿动作"，失败应<b>记日志、继续处理其他明细</b>，
     * 而不是让整个订单状态回滚。
     */
    private void doCloseOrder(Long orderId) {
        // 1.标记订单为已关闭（状态更新独立提交，库存补偿失败不影响关单）
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(OrderStatus.CLOSED.getValue());
        order.setCloseTime(LocalDateTime.now());
        updateById(order);

        // 2.恢复库存（尽力而为，失败不阻断关单）
        List<OrderDetail> details = detailService.list(
                new QueryWrapper<OrderDetail>().eq("order_id", orderId));
        if (details.isEmpty()) {
            return;
        }
        for (OrderDetail detail : details) {
            try {
                ItemDTO item = itemClient.queryItemById(detail.getItemId());
                if (item == null) {
                    // 商品不存在：无法恢复，记日志后继续处理下一条
                    log.warn("取消订单恢复库存跳过：商品不存在 itemId={}, orderId={}",
                            detail.getItemId(), orderId);
                    continue;
                }
                item.setStock(item.getStock() + detail.getNum());
                itemClient.updateItem(item);
            } catch (Exception e) {
                // 捕获所有异常（Feign 401/超时/连接拒绝等），继续处理剩余明细。
                // 库存恢复属于补偿动作，失败只记日志——让它抛出就会触发 MQ 无限重投。
                log.error("取消订单恢复库存失败：orderId={}, itemId={}, num={}, 原因={}",
                        orderId, detail.getItemId(), detail.getNum(), e.getMessage());
            }
        }
    }

    /**
     * 买家确认收货，交易完成。
     *
     * <p><b>状态流转</b>：已支付(2) → 已完成(4)。
     * 只允许从「已支付」进入 —— 未支付的订单还没发货，谈不上收货。
     *
     * <p><b>为什么确认收货要标记商品下架</b>：
     * 二手商品是<b>独占</b>的，确认收货意味着这件物品已经卖掉，
     * 必须下架，否则还能被再次下单购买。
     * 若标记失败只记日志不抛异常 —— 订单本身已完成，商品能否下架
     * 是次要问题，让用户看到"确认收货失败"反而更糟。
     *
     * <p><b>幂等</b>：重复确认时状态已不是 2，会被拒绝并给出明确提示，
     * 不会重复下架商品。
     */
    @Override
    public void confirmReceipt(Long orderId, Long userId) {
        Order order = getById(orderId);
        if (order == null) {
            throw new BadRequestException("订单不存在");
        }
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BadRequestException("订单不属于当前用户");
        }
        Integer status = order.getStatus();
        if (status == null || status != OrderStatus.PAID.getValue()) {
            throw new BadRequestException("订单当前状态为「" + OrderStatus.textOf(status)
                    + "」，只有已支付订单可以确认收货");
        }

        // 1.订单进入终态
        Order update = new Order();
        update.setId(orderId);
        update.setStatus(OrderStatus.FINISHED.getValue());
        update.setConsignTime(LocalDateTime.now());
        update.setEndTime(LocalDateTime.now());
        updateById(update);
        log.info("订单确认收货完成 orderId={}, userId={}", orderId, userId);

        // 2.把商品标记为已售（下架），避免重复出售
        List<OrderDetail> details = detailService.list(
                new QueryWrapper<OrderDetail>().eq("order_id", orderId));
        for (OrderDetail detail : details) {
            try {
                // 用专用的 markAsSold 而非 updateItem：
                // updateItem 有归属校验（只允许卖家改自己的物品），
                // 买家确认收货时会被拒；且它会把 status 强制置 null。
                itemClient.markAsSold(detail.getItemId());
                log.info("商品已标记为已售 itemId={}（因订单 {} 确认收货）",
                        detail.getItemId(), orderId);
            } catch (Exception e) {
                // 订单已完成是主流程，商品下架失败只记日志
                log.error("确认收货时标记商品已售失败：orderId={}, itemId={}, 原因={}",
                        orderId, detail.getItemId(), e.getMessage());
            }
        }
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
