package com.venueflow.service;

import com.venueflow.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统计两件套：HyperLogLog 访客 UV + bitmap 签到。
 *
 * ───────────────────── ⭐高频对比（必背） ─────────────────────
 * HyperLogLog（去重计数，允许误差）：
 *  - 12KB 固定内存，不管来一百万还是一亿访客，都是 12KB
 *  - 代价：0.81% 标准误差，且拿不到具体元素（只有数没有明细）
 *  - 用途：UV 统计这种"要量级不要精确名单"的场景
 * bitmap（签到，精确）：
 *  - 一天占 1 bit，一个月 31 bit ≈ 4 字节；百万用户一个月 ≈ 4MB
 *  - 精确到"谁在哪天签了"，BITCOUNT 数量、BITFIELD 取区间、位运算算连续
 *  - 用途：签到、在线状态、活跃标记
 * 一句话总结：要【大约多少】用 HLL，要【具体谁在何时】用 bitmap。
 * ─────────────────────────────────────────────────────────────
 */
@Service
public class StatsService {

    public static final String UV_KEY_PREFIX = "stats:uv:";
    public static final String SIGN_KEY_PREFIX = "stats:sign:";

    private final StringRedisTemplate redisTemplate;

    public StatsService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static String today() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    // ───────────────────────── HyperLogLog：每日 UV ─────────────────────────

    /**
     * 记录一次访问。此处用登录 userId 当访客标识（演示）；
     * ⭐生产辨析：UV 真正要统计的是"游客"，强制登录反而失真——应该用 IP / 设备指纹。
     * PFADD 自动去重：同一元素加一百次也只记一个
     */
    public void visit() {
        Long userId = UserHolder.get().getId();
        redisTemplate.opsForHyperLogLog().add(UV_KEY_PREFIX + today(), userId.toString());
    }

    /** 当日 UV（PFCOUNT） */
    public Long uvToday() {
        return redisTemplate.opsForHyperLogLog().size(UV_KEY_PREFIX + today());
    }

    // ───────────────────────── bitmap：签到与连续签到 ─────────────────────────

    /** 签到：当月 bit 图的第 dayOfMonth 位（从 0 起）置 1 */
    public void sign() {
        Long userId = UserHolder.get().getId();
        LocalDateTime now = LocalDateTime.now();
        String key = SIGN_KEY_PREFIX + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        redisTemplate.opsForValue().setBit(key, now.getDayOfMonth() - 1, true);
    }

    /**
     * 连续签到天数：从今天（最低位）往月初方向数连续的 1。
     * BITFIELD 把本月已签到天数取成一个无符号整数（第 i 天签了 = 第 i 位是 1），
     * 然后在 Java 里从低位往高位扫——位运算 O(天数)，Redis 只出一次 IO
     */
    public int signStreak() {
        Long userId = UserHolder.get().getId();
        LocalDateTime now = LocalDateTime.now();
        String key = SIGN_KEY_PREFIX + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int day = now.getDayOfMonth();
        List<Long> res = redisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day))
                        .valueAt(0));
        if (res == null || res.isEmpty() || res.get(0) == null) {
            return 0;
        }
        long num = res.get(0);
        int streak = 0;
        while ((num & 1) == 1) {   // 最低位=今天；是 1 就计入并右移看昨天
            streak++;
            num >>>= 1;
        }
        return streak;
    }
}
