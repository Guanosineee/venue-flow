package com.venueflow.mq;

import com.venueflow.config.RabbitConfig;
import com.venueflow.dto.SeckillMessage;
import com.venueflow.service.TicketOrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 秒杀下单消息消费者：DB 按 MQ 投递节奏落库。
 *
 * ⭐面试点：ack 策略——Spring AMQP 默认"方法正常返回即自动 ack"；
 * 抛异常则 nack + requeue（消息重回队列）。所以消费逻辑必须【幂等 + 不主动抛可预期异常】：
 * 可预期的失败（库存没了/已下过单）静默返回；不可预期的异常才让它重投，且要配死信队列兜底防无限循环。
 */
@Component
public class OrderMessageListener {

    private final TicketOrderService ticketOrderService;

    public OrderMessageListener(TicketOrderService ticketOrderService) {
        this.ticketOrderService = ticketOrderService;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_QUEUE)
    public void listenSeckillOrder(SeckillMessage msg) {
        ticketOrderService.createSeckillOrder(msg);
    }
}
