package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.service.FollowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/follow")
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    /** 关注/取关金牌讲解员 */
    @PutMapping("/{id}/{isFollow}")
    public Result<Void> follow(@PathVariable Long id, @PathVariable boolean isFollow) {
        followService.follow(id, isFollow);
        return Result.ok();
    }

    /** 共同关注（SINTER 交集） */
    @GetMapping("/common/{id}")
    public Result<List<String>> common(@PathVariable Long id) {
        return Result.ok(followService.commonFollows(id));
    }
}
