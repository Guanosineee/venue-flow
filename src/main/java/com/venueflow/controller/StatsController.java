package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.service.StatsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 记录一次访问（PFADD，自动去重） */
    @PostMapping("/visit")
    public Result<Void> visit() {
        statsService.visit();
        return Result.ok();
    }

    /** 当日 UV（PFCOUNT） */
    @GetMapping("/uv")
    public Result<Long> uv() {
        return Result.ok(statsService.uvToday());
    }

    /** 签到（bitmap 当月第 day 位） */
    @PostMapping("/sign")
    public Result<Void> sign() {
        statsService.sign();
        return Result.ok();
    }

    /** 连续签到天数 */
    @GetMapping("/sign/count")
    public Result<Integer> signCount() {
        return Result.ok(statsService.signStreak());
    }
}
