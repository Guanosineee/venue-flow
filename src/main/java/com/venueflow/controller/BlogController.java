package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.entity.Blog;
import com.venueflow.service.BlogService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    /** 点赞/取消点赞（再点一次=取消） */
    @PutMapping("/like/{id}")
    public Result<Void> like(@PathVariable Long id) {
        blogService.like(id);
        return Result.ok();
    }

    /** 点赞排行榜：最早点赞的前 5 位 */
    @GetMapping("/likes/{id}")
    public Result<List<String>> likeTop5(@PathVariable Long id) {
        return Result.ok(blogService.likeTop5(id));
    }

    @GetMapping("/hot")
    public Result<List<Blog>> hot() {
        return Result.ok(blogService.hot());
    }
}
