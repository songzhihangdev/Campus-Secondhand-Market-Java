package com.campus.seckill.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀 MQ 配置。
 *
 * <p><b>幂等投递是必需的</b>：RabbitMQ 只保证「至少一次」投递，
 * 消费者必须自己处理重复消息（见 SeckillStockConsumer）。
 * 开启 {@code publisher-confirm} 与 {@code publisher-returns} 可让发送方感知失败。
 */
@Configuration
public class RabbitMQConfig {

    public static final String SECKILL_EXCHANGE = "campus.seckill.exchange";
    public static final String SECKILL_QUEUE = "campus.seckill.queue";
    public static final String SECKILL_ROUTING_KEY = "campus.seckill";
    public static final String SECKILL_DLQ = "campus.seckill.dlq";

    /** 秒杀队列：堆积上限 10000，超出后丢弃并记录（秒杀场景可接受丢失极端尾部） */
    @Bean
    public Queue seckillQueue() {
        return new Queue(SECKILL_QUEUE, true, false, false,
                java.util.Map.of("x-max-length", 10000));
    }

    /** 死信队列：多次重试仍失败的消息进这里，便于排查 */
    @Bean
    public Queue seckillDeadQueue() {
        return new Queue(SECKILL_DLQ, true);
    }

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange seckillDeadExchange() {
        return new DirectExchange(SECKILL_EXCHANGE + ".dlx", true, false);
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue())
                .to(seckillExchange()).with(SECKILL_ROUTING_KEY);
    }

    @Bean
    public Binding seckillDeadBinding() {
        return BindingBuilder.bind(seckillDeadQueue())
                .to(seckillDeadExchange()).with(SECKILL_ROUTING_KEY);
    }

    /** 消息体用 JSON 序列化，便于排查与跨语言消费 */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory factory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMessageConverter(converter);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 生产确认失败：消息未到达 Broker，需业务侧补偿
                System.err.println("[秒杀] 消息未被 Broker 确认: " + cause);
            }
        });
        template.setReturnsCallback(returned -> {
            System.err.println("[秒杀] 消息无法路由到队列: " + returned.getRoutingKey());
        });
        return template;
    }
}
