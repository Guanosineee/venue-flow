package com.venueflow.config;

import com.venueflow.entity.Ticket;
import com.venueflow.mapper.TicketMapper;
import com.venueflow.service.TicketService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把所有秒杀票的库存同步进 Redis（并清掉一人一单的用户集合）。
 *
 * ⭐说明：生产环境不会"每次启动重置"——库存同步有自己的运维流程（改库存走管理端，
 * 增量 set 回 Redis）。这里这么做是为了【压测可重复】：每轮实验都从同一初始状态出发，
 * 这也是压测方法论的一部分——变量唯一（代码版本），初始状态固定。
 */
@Component
public class StockInitRunner implements ApplicationRunner {

    private final TicketMapper ticketMapper;
    private final StringRedisTemplate redisTemplate;

    public StockInitRunner(TicketMapper ticketMapper, StringRedisTemplate redisTemplate) {
        this.ticketMapper = ticketMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Ticket> tickets = ticketMapper.selectList(null);
        for (Ticket t : tickets) {
            redisTemplate.opsForValue().set(
                    TicketService.STOCK_KEY_PREFIX + t.getId(), String.valueOf(t.getStock()));
            redisTemplate.delete(TicketService.USER_SET_KEY_PREFIX + t.getId());
        }
    }
}
