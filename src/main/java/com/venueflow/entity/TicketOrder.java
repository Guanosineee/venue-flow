package com.venueflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_ticket_order")
public class TicketOrder {
    // ⭐面试点：订单 id 不用自增——用 RedisIdWorker 全局唯一ID（趋势递增）
    // 为什么：1) 分库分表后自增会撞；2) 无序 UUID 让 InnoDB B+ 树频繁页分裂
    @TableId(type = IdType.INPUT)
    private Long id;
    private Long userId;
    private Long ticketId;
    private BigDecimal payAmount;
    private LocalDateTime createTime;
}
