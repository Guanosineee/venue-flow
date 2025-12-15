package com.venueflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.venueflow.entity.Blog;
import com.venueflow.entity.User;
import com.venueflow.mapper.BlogMapper;
import com.venueflow.mapper.UserMapper;
import com.venueflow.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 展评点赞 + 点赞排行榜。
 *
 * ⭐面试点：点赞为什么用 ZSET 不用 SET？
 *  需求是"最早点赞的 5 位上榜"（点赞排行榜）——SET 只能去重记"谁赞过"，
 *  ZSET 的 score 存点赞时间戳，ZRANGE 0 4 直接按时间升序拿前五。
 *  若只要去重不要顺序，SET 就够——数据结构跟着需求走，别炫技。
 * ⭐一人一赞的原子性：先查 score 判定，再 ZADD/ZREM + DB 冗余计数更新。
 *  高并发下极小概率"重复点赞"由 DB 侧 liked 计数与 ZSET 的最终一致消化（点赞非资金场景，可接受）
 */
@Service
public class BlogService {

    public static final String LIKE_KEY_PREFIX = "blog:liked:";   // ZSET: member=userId, score=点赞时间戳

    private final BlogMapper blogMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    public BlogService(BlogMapper blogMapper, UserMapper userMapper, StringRedisTemplate redisTemplate) {
        this.blogMapper = blogMapper;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
    }

    /** 点赞/取消点赞（再点一次=取消） */
    @Transactional
    public void like(Long blogId) {
        Long userId = UserHolder.get().getId();
        String key = LIKE_KEY_PREFIX + blogId;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {   // 没赞过 → 点赞
            redisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            blogMapper.update(null, Wrappers.<Blog>update()
                    .setSql("liked = liked + 1").eq("id", blogId));
        } else {               // 赞过 → 取消
            redisTemplate.opsForZSet().remove(key, userId.toString());
            blogMapper.update(null, Wrappers.<Blog>update()
                    .setSql("liked = liked - 1").eq("id", blogId));
        }
    }

    /** 点赞排行榜：最早点赞的前 5 位用户昵称 */
    public List<String> likeTop5(Long blogId) {
        Set<String> top5 = redisTemplate.opsForZSet()
                .range(LIKE_KEY_PREFIX + blogId, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return List.of();
        }
        List<Long> ids = top5.stream().map(Long::parseLong).toList();
        return userMapper.selectBatchIds(ids).stream().map(User::getNickName).toList();
    }

    /** 热门展评（演示用 DB 排序；量大后同样应下沉为 ZSET 全局热榜） */
    public List<Blog> hot() {
        return blogMapper.selectList(Wrappers.<Blog>query()
                .orderByDesc("liked").last("LIMIT 5"));
    }
}
