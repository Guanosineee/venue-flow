package com.venueflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.venueflow.entity.Ticket;
import com.venueflow.entity.TicketOrder;
import com.venueflow.mapper.TicketMapper;
import com.venueflow.mapper.TicketOrderMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 门票服务：秒杀券创建 + 库存预热进 Redis + 压测重置。
 */
@Service
public class TicketService {

    public static final String STOCK_KEY_PREFIX = "seckill:stock:";
    public static final String USER_SET_KEY_PREFIX = "seckill:users:";

    private final TicketMapper ticketMapper;
    private final TicketOrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;

    public TicketService(TicketMapper ticketMapper, TicketOrderMapper orderMapper,
                         StringRedisTemplate redisTemplate) {
        this.ticketMapper = ticketMapper;
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建秒杀票：落库 + 库存预热进 Redis。
     *
     * ⭐面试点：库存为什么要放 Redis？
     *  - 秒杀的瞬间并发全部砸在"判断库存够不够"这一步。DB 判库存=行锁串行，
     *    几百 QPS 就开始排队，锁等待超时雪崩
     *  - Redis 内存操作单机 10w+ QPS，decr/sismember 都是微秒级
     *  - 最终一致性：Redis 判定扣减（挡住超额请求）→ MQ → DB 扣减（真实账本）
     */
    public void addSeckill(Ticket ticket) {
        ticket.setCreateTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
        redisTemplate.opsForValue().set(
                STOCK_KEY_PREFIX + ticket.getId(), String.valueOf(ticket.getStock()));
    }

    public Ticket byId(Long id) {
        return ticketMapper.selectById(id);
    }

    /** 压测重置：回到初始状态（DB 库存、清订单、Redis 库存与用户集合）——保证每轮实验同一起点 */
    public void resetSeckill(Long ticketId, Integer stock) {
        ticketMapper.update(null, Wrappers.<Ticket>update().set("stock", stock).eq("id", ticketId));
        orderMapper.delete(new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getTicketId, ticketId));
        redisTemplate.opsForValue().set(STOCK_KEY_PREFIX + ticketId, String.valueOf(stock));
        redisTemplate.delete(USER_SET_KEY_PREFIX + ticketId);
    }
}
