package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.entity.Ticket;
import com.venueflow.service.TicketService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ticket")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 创建秒杀票（落库 + 库存预热进 Redis） */
    @PostMapping("/seckill")
    public Result<Void> addSeckill(@RequestBody Ticket ticket) {
        ticketService.addSeckill(ticket);
        return Result.ok();
    }

    @GetMapping("/{id}")
    public Result<Ticket> byId(@PathVariable Long id) {
        return Result.ok(ticketService.byId(id));
    }

    /** 压测重置：库存重置为 stock、清空该票订单、重置 Redis 库存与用户集合 */
    @PostMapping("/reset")
    public Result<Void> reset(@RequestParam Long ticketId, @RequestParam Integer stock) {
        ticketService.resetSeckill(ticketId, stock);
        return Result.ok();
    }
}
