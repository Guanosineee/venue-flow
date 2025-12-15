package com.venueflow.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.venueflow.entity.Follow;
import com.venueflow.entity.User;
import com.venueflow.mapper.FollowMapper;
import com.venueflow.mapper.UserMapper;
import com.venueflow.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 关注金牌讲解员 + 共同关注。
 *
 * ⭐面试点：关注关系为什么天然适合 SET？
 *  - 去重（不能重复关注）
 *  - 共同关注 = 两个集合的交集 SINTER，一条命令出结果
 *  - 我关注的人 / 关注我的人 = 两张方向不同的 SET，互为倒排
 *  ⭐DB 与 Redis 双写顺序：先写 DB，成功才写 Redis（以 DB 为准）——
 *  反过来（先缓存后库）库失败时缓存里就是脏数据，且无人纠正
 */
@Service
public class FollowService {

    public static final String FOLLOW_KEY_PREFIX = "follows:";   // SET: 我关注的人

    private final FollowMapper followMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;

    public FollowService(FollowMapper followMapper, UserMapper userMapper, StringRedisTemplate redisTemplate) {
        this.followMapper = followMapper;
        this.userMapper = userMapper;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public void follow(Long followUserId, boolean isFollow) {
        Long userId = UserHolder.get().getId();
        if (isFollow) {
            Follow f = new Follow();
            f.setUserId(userId);
            f.setFollowUserId(followUserId);
            if (followMapper.insert(f) > 0) {   // uk_user_follow 防重复；DB 成功才写缓存
                redisTemplate.opsForSet().add(FOLLOW_KEY_PREFIX + userId, followUserId.toString());
            }
        } else {
            followMapper.delete(Wrappers.<Follow>query()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            redisTemplate.opsForSet().remove(FOLLOW_KEY_PREFIX + userId, followUserId.toString());
        }
    }

    /** 共同关注：SINTER 求交集。缓存缺失时退化为 DB 查询求交（保证正确性优先） */
    public List<String> commonFollows(Long targetUserId) {
        Long me = UserHolder.get().getId();
        Set<String> inter = redisTemplate.opsForSet().intersect(
                FOLLOW_KEY_PREFIX + me, FOLLOW_KEY_PREFIX + targetUserId);
        if (inter == null || inter.isEmpty()) {
            return List.of();
        }
        List<Long> ids = inter.stream().map(Long::parseLong).toList();
        return userMapper.selectBatchIds(ids).stream().map(User::getNickName).toList();
    }
}
