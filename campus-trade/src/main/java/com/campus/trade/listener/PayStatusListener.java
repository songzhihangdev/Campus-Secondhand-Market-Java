package com.campus.trade.listener;

import com.campus.trade.domain.po.Order;
import com.campus.trade.service.IOrderService;
import com.rabbitmq.client.AMQP;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PayStatusListener {

    private final IOrderService orderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "trade.pay.success.queue", durable = "true"),
            exchange = @Exchange(name = "pay.direct"),
            key = "pay.success"
    ))
    public void listenPaySuccess(Long id){
        //查询订单
        Order order = orderService.getById(id);

        //判断订单状态是否为未支付
        if(order == null|| order.getStatus() != 1){
            return ;
        }

        orderService.markOrderPaySuccess(id);
    }
}

