package com.campus.item.listener;

import com.campus.item.domain.dto.StockChangeDTO;
import com.campus.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockChangeListener {

    private final IItemService itemService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "item.stock.change.queue", durable = "true"),
            exchange = @Exchange(name = "item.direct"),
            key = "stock.change"
    ))
    public void listenStockChange(StockChangeDTO message) {
        // 以数据库最新库存为准，回写Elasticsearch
        itemService.updateEsStock(message.getItemId());
    }
}
