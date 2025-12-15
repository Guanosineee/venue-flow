package com.venueflow.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：TopicExchange + 一个订单队列。
 *
 * ⭐面试点：MQ 在秒杀里的角色
 *  - 异步：Redis 判定通过即返回订单号，落库慢慢来（用户体验从"等 DB"变成"秒回"）
 *  - 削峰：瞬时 10w 请求 → Redis 扛判定 → MQ 排队 → DB 按自己的节奏消费（洪峰被拉平成水渠）
 *  - 解耦：订单库以后挂营销/积分，只要再绑个队列，不动主流程
 *
 * ⭐面试点：消息不丢三板斧（能主动说=加分）
 *  - 生产者：publisher confirm 确认机制（nack/超时重发）
 *  - MQ：队列+消息持久化（durable + 磁盘），集群镜像/仲裁队列
 *  - 消费者：手动 ack（处理完才确认），失败重回队列/死信队列
 *  → 但重复投递无法根除，所以消费端必须【幂等】（本项目：订单表 uk_user_ticket 唯一键兜底）
 */
@Configuration
public class RabbitConfig {

    public static final String ORDER_QUEUE = "order.queue";
    public static final String ORDER_EXCHANGE = "order.topic";
    public static final String ORDER_ROUTING_KEY = "order.seckill";

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE).build();   // durable：broker 重启队列不丢
    }

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Binding orderBinding(Queue orderQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderQueue).to(orderExchange).with(ORDER_ROUTING_KEY);
    }

    /** JSON 序列化（默认 Java 序列化又胖又脆，生产没人用） */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
