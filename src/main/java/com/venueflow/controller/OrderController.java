package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.service.TicketOrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/order")
public class OrderController {

    private final TicketOrderService ticketOrderService;

    @Value("${venue-flow.unsafe:false}")
    private boolean unsafe;

    public OrderController(TicketOrderService ticketOrderService) {
        this.ticketOrderService = ticketOrderService;
    }

    /** 秒杀下单（正式版：Lua 原子判定 + MQ 异步落库），返回订单号 */
    @PostMapping("/seckill/{ticketId}")
    public Result<Long> seckill(@PathVariable Long ticketId) {
        return Result.ok(ticketOrderService.seckill(ticketId));
    }

    /** 翻车版（仅 venue-flow.unsafe=true 开放）：查-判-扣无并发控制，压测复现超卖 */
    @PostMapping("/seckill-naive/{ticketId}")
    public Result<Long> naiveSeckill(@PathVariable Long ticketId) {
        if (!unsafe) {
            return Result.fail("翻车版仅 unsafe=true 开放（application.yml）");
        }
        return Result.ok(ticketOrderService.naiveSeckill(ticketId));
    }

    /** 压测对账：DB 库存 / Redis 库存 / 订单数 / 是否超卖，一眼看全 */
    @GetMapping("/stat/{ticketId}")
    public Result<Map<String, Object>> stat(@PathVariable Long ticketId) {
        return Result.ok(ticketOrderService.stat(ticketId));
    }
}
