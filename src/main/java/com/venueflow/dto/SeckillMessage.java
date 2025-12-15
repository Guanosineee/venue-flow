package com.venueflow.dto;

// MQ 秒杀下单消息体。
// ⭐面试点：MQ 三作用之"异步"——Redis 校验通过就算下单成功（先回订单号），
// 落库由消费者按 DB 自己的节奏来；用户拿着订单号稍后查询即可
// ⭐面试点：消息可能重复投递（生产者重发/消费者 ack 丢失），所以消费端必须幂等——
// 本项目靠订单表 uk_user_ticket 唯一键兜底
public record SeckillMessage(Long ticketId, Long userId, Long orderId) {
}
