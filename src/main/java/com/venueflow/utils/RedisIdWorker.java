package com.venueflow.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一订单 ID：64bit = 1 符号位 + 31bit 时间戳 + 32bit 序列号
 *
 * ⭐面试点：为什么不用 UUID / 数据库自增？
 *  - UUID 无序 → 做 InnoDB 聚簇索引主键会频繁页分裂（B+ 树叶子要维持有序，随机插入=来回挪页）
 *  - 数据库自增 → 分库分表后各库各自为政会撞号；且单调递增的 id 泄露单量（竞对每天抓你接口算订单数）
 *  - 本方案：趋势递增（对 B+ 树友好）+ 全局唯一（序列号由 Redis INCR 原子保证）
 *
 * ⭐细节：序列号的 Redis key 按天分（icr:order:20260929）
 *  - 序列 32bit 上限 42 亿，一天绝对用不完，但要防 key 无限增长
 *  - 附赠可读性：从 id 能反推出大致下单日期
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1735689600L; // 2025-01-01 00:00:00 UTC（起始纪元）
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate redisTemplate;

    public RedisIdWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public long nextId(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        long epochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = epochSecond - BEGIN_TIMESTAMP;

        String date = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // INCR：Redis 单线程原子自增，天然解决并发取号
        long count = redisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);

        return timestamp << COUNT_BITS | count;
    }
}
