package com.campus.trade.listener;

import com.campus.api.client.PayClient;
import com.campus.api.dto.PayOrderDTO;
import com.campus.trade.constants.MQConstans;
import com.campus.trade.domain.po.Order;
import com.campus.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时未支付的延迟消息监听器。
 *
 * <p><b>关键设计：绝不让异常逃出监听方法</b>
 *
 * <p>RabbitMQ 默认 {@code requeue=true}：监听方法抛异常 → 消息重回队首 → 立即重投。
 * 实测踩过：关单时恢复库存的远程调用失败（item 返回 401 / 连接拒绝），
 * 异常一路抛出 → 消息无限重投 → 每次失败打 40+ 行堆栈 → 日志刷到上万条，
 * 最终消费者被 MQ 判定 {@code unresponsive} 直接关闭通道，<b>整条队列的订单都失去超时关单能力</b>。
 *
 * <p>因此这里用 try-catch 兜住所有异常并<b>吞掉</b>：
 * 延迟关单本就是"尽力而为"的补偿动作，失败不应拖垮消费者。
 * 需要人工介入的场景靠 WARN 日志 + 监控告警发现，而不是靠无限重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    private final IOrderService orderService;
    private final PayClient payClient;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = MQConstans.DELAY_ORDER_QUEUE_NAME, durable = "true"),
            exchange = @Exchange(name = MQConstans.DELAY_EXCHANGE_NAME,delayed = "true"),
            key = "delay.order.query"
    ))
    public void listenOrderDelayMessage(Long orderId) {
        // 兜底 try-catch：确保任何异常都不会逃出监听方法触发 MQ 无限重投
        try {
            doCancel(orderId);
        } catch (Exception e) {
            // 只打一行摘要（不打堆栈，避免刷屏），含 orderId 便于人工介入
            log.warn("订单超时关单处理失败（消息已丢弃，不重试）：orderId={}, 原因={}",
                    orderId, e.getMessage());
        }
    }

    private void doCancel(Long orderId) {
        //1.查询订单状态，检查是否已支付
        Order order = orderService.getById(orderId);
        //2.订单不存在 / 已支付 / 已关闭，都无需处理
        //   注意这里是幂等的关键：重复投递时 status 已非 1，直接返回
        if (order == null || order.getStatus() != 1) {
            return;
        }
        //3.查询订单支付流水
        PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(order.getId());
        if (payOrder != null && payOrder.getStatus() == 3) {
            //已支付，则更新订单状态为已支付
            orderService.markOrderPaySuccess(orderId);
            return;
        }
        //4.未支付（且支付流水也不存在），则关闭订单并恢复库存
        orderService.closeOrderQuietly(orderId);
    }
}
