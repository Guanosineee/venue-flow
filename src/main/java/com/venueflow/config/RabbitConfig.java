package com.venueflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 环形拓扑：TopicExchange + 订单队列 + 死信旁路。
 *
 * ⭐面试点：MQ 在秒杀里的角色
 *  - 异步：Redis 判定通过即返回订单号，落库慢慢来（用户体验从"等 DB"变成"秒回"）
 *  - 削峰：瞬时 10w 请求 → Redis 扛判定 → MQ 排队 → DB 按自己的节奏消费（洪峰被拉平成水渠）
 *  - 解耦：订单库以后挂营销/积分，只要再绑个队列，不动主流程
 *
 * ⭐面试点：消息不丢三板斧（能主动说=加分）
 *  - 生产者：publisher confirm 确认机制（nack/超时重发）——见下方 RabbitTemplate 的 ConfirmCallback
 *  - MQ：队列+消息持久化（durable + 磁盘），集群镜像/仲裁队列
 *  - 消费者：Spring AMQP 默认 AUTO ack（方法正常返回即 ack；抛异常则 nack），
 *    配合 default-requeue-rejected=false：失败消息不无限重回，改走死信旁路人工/补偿
 *  → 但重复投递无法根除，所以消费端必须【幂等】（本项目：订单表 uk_user_ticket 唯一键兜底）
 *
 * ⭐踩坑记录：order.queue 加了死信参数（x-dead-letter-exchange）后，与 RabbitMQ 上已存在的旧队列
 *  声明参数不一致 → PRECONDITION_FAILED 启动炸。同名队列参数永不可变，改参数必须先删旧队列：
 *  wsl docker exec expomeet-mq rabbitmqctl delete_queue order.queue
 */
@Configuration
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    public static final String ORDER_QUEUE = "order.queue";
    public static final String ORDER_EXCHANGE = "order.topic";
    public static final String ORDER_ROUTING_KEY = "order.seckill";
    public static final String DLX_EXCHANGE = "order.dlx";
    public static final String DLX_QUEUE = "order.dlx.queue";
    public static final String DLX_ROUTING_KEY = "order.dead";

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(ORDER_QUEUE)              // durable：broker 重启队列不丢
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)        // 消费失败(requeue=false)的旁路
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public TopicExchange orderExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Binding orderBinding(Queue orderQueue, TopicExchange orderExchange) {
        return BindingBuilder.bind(orderQueue).to(orderExchange).with(ORDER_ROUTING_KEY);
    }

    // ── 死信旁路：被拒/过期/超长的消息进这里留档，人工排查或补偿任务处理 ──
    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with(DLX_ROUTING_KEY);
    }

    /** JSON 序列化（默认 Java 序列化又胖又脆，生产没人用） */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * ⭐生产端 confirm 的落地：自定义 RabbitTemplate 覆盖 Boot 自动配置的默认模板。
     * mandatory=true + ReturnsCallback：消息到了 broker 但路由不到任何队列 → 回调（默认是静默丢弃）。
     * ConfirmCallback：broker 收到/没收到的回执；生产环境在这里做重发或记补偿表。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                // 生产环境姿势：重发 / 写补偿表由后台任务扫表重投
                log.error("[MQ confirm] 消息未被 broker 确认: {} cause: {}", correlationData, cause);
            }
        });
        template.setReturnsCallback(returned ->
                log.error("[MQ return] 消息路由不到队列: exchange={} routingKey={} replyText={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
