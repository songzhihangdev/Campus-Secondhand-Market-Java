package com.campus.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.trade.domain.dto.OrderFormDTO;
import com.campus.trade.domain.po.Order;
/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
public interface IOrderService extends IService<Order> {

    Long createOrder(OrderFormDTO orderFormDTO);

    void markOrderPaySuccess(Long orderId);

    /**
     * 用户主动取消订单（<b>带状态与归属校验</b>）。
     *
     * <p>与 {@link #closeOrderQuietly} 的分工：本方法是对外接口，
     * 遇到非法状态（已支付/已关闭等）必须抛异常返回 400，不能静默放过。
     *
     * <p><b>为什么必须校验</b>：原实现直接 {@code updateById(status=5)}，
     * 导致<b>已支付订单也能被取消</b>。而关单逻辑会把库存加回去，
     * 于是出现「钱已退 + 库存又加」的错误，商品可被无限重复购买。
     *
     * @param orderId 订单号
     * @param userId  当前登录用户（来自 UserContext），用于归属校验
     * @throws com.campus.common.exception.BadRequestException 订单不存在 / 状态不允许 / 非本人订单
     */
    void cancelOrderByUser(Long orderId, Long userId);

    /**
     * 静默关闭订单（供 MQ 延迟消息调用，<b>不抛异常</b>）。
     *
     * <p>只在订单处于「未支付」时才关闭。{@code OrderDelayMessageListener}
     * 已提前判过状态，这里是第二道防线 —— 状态不匹配时直接返回，
     * <b>不恢复库存</b>，避免对同一订单重复补偿。
     *
     * @param orderId 订单号
     */
    void closeOrderQuietly(Long orderId);

    /**
     * 买家确认收货，交易完成。
     *
     * <p>这是二手交易的<b>最后一环</b>：确认收货意味着买家已收到货，
     * 订单进入终态（已完成），同时把商品标记为已售（下架）。
     *
     * @param orderId 订单号
     * @param userId  当前登录用户，必须是订单买家
     * @throws com.campus.common.exception.BadRequestException 订单不存在 / 非本人 / 状态不允许
     */
    void confirmReceipt(Long orderId, Long userId);
}
