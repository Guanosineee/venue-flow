package com.venueflow.utils;

import lombok.Data;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存工具类——缓存三兄弟（穿透/击穿/雪崩）的完整实现，本项目的心脏。
 *
 * ───────────────────────── ⭐面试总纲（必背对比表） ─────────────────────────
 * 穿透：请求的 key 在缓存和 DB 里【都没有】（恶意伪造 id）→ 每次都打穿到 DB
 *      解法：①空值缓存（本类 queryWithPassThrough，简单通用）
 *           ②布隆过滤器（前置拦截，内存省但有误判率、删除困难，知道即可）
 * 击穿：【热点 key 恰好过期】瞬间，成千上万并发同时回源重建 → DB 瞬间被打爆
 *      解法：①互斥锁（queryWithMutex）：只放一个线程重建，其余等待。强一致但牺牲吞吐
 *           ②逻辑过期（queryWithLogicalExpire）：物理永不过期+字段记逻辑过期时间，
 *             过期后由抢到锁的线程【异步】重建，期间所有人拿到旧值。高可用但有脏读窗口
 *      选哪个：倾向于逻辑过期——缓存的意义就是可用性，旧值好过报错/等待（说得出 trade-off 就是加分）
 * 雪崩：大量 key【同时过期】或 Redis 宕机 → 请求洪峰整体砸向 DB
 *      解法：①TTL 加随机抖动（本类 set 里做了）②Redis 高可用集群 ③服务限流/熔断降级兜底
 * ─────────────────────────────────────────────────────────────────────────
 */
@Component
public class CacheClient {

    /** 空值缓存的 TTL：要短（2 分钟）——它防的是恶意/高频穿透，不是真数据 */
    private static final long CACHE_NULL_TTL_MINUTES = 2;
    private static final String LOCK_PREFIX = "lock:cache:";
    private static final ExecutorService REBUILD_POOL = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate redisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 逻辑过期包装结构（存进 Redis 的 JSON 长这样） */
    @Data
    public static class RedisData {
        private Object data;    // 真实业务数据
        private Long expireAt;  // 逻辑过期时间（epochMilli）——注意 key 本身永不设置 TTL
    }

    /** 普通写入。⭐防雪崩：TTL 在 [time, 2*time) 随机抖动，避免同时过期 */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        long jitter = ThreadLocalRandom.current().nextLong(time + 1); // [0, time]
        redisTemplate.opsForValue().set(key, JsonUtils.toJSON(value), time + jitter, unit);
    }

    /** 逻辑过期写入（配合预热使用）：key 不设 TTL，过期与否看 RedisData.expireAt */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData rd = new RedisData();
        rd.setData(value);
        rd.setExpireAt(System.currentTimeMillis() + unit.toMillis(time));
        redisTemplate.opsForValue().set(key, JsonUtils.toJSON(rd));
    }

    // ───────────────────────── 穿透防护版 ─────────────────────────

    /**
     * 查询（防穿透）：缓存 miss → 查库；库也没有 → 写【空字符串】短 TTL。
     * ⭐原理：让"不存在"本身也成为一个缓存值——第二次同样的恶意请求就命中空值，不再碰 DB。
     * 代价：额外的 key 占内存（所以 TTL 要短），且 DB 若后来新增了该数据要等空值过期才可见（最终一致）
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json != null && !json.isEmpty()) {
            return JsonUtils.parse(json, type);          // 真命中
        }
        if (json != null) {                              // json == "" 空值缓存命中
            return null;
        }
        R r = dbFallback.apply(id);                      // miss → 回源
        if (r == null) {
            redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL_MINUTES, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);                    // set 内含随机 TTL 防雪崩
        return r;
    }

    // ───────────────────────── 击穿防护版一：互斥锁 ─────────────────────────

    /**
     * 查询（防击穿·互斥锁版）：缓存 miss 时用 SETNX 抢锁，只有抢到的一个线程查库重建，
     * 其余线程自旋重读缓存。全部线程拿到的是【新值】——强一致，代价是排队等待。
     * ⭐双重检查（拿锁后再查一次缓存）是本函数的灵魂：排队进的来时数据可能已被重建，
     * 不再查一遍就白回源了——和 DCL 单例的第二次判空同一思想
     */
    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                    Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_PREFIX + key;
        try {
            R r = readCacheOrNull(key, type);
            if (r != null || isNullMarker(key)) return r;

            boolean locked = false;
            while (!locked) {
                locked = tryLock(lockKey, 10);
                if (!locked) {
                    Thread.sleep(50);                    // 没抢到：睡一会再重读缓存
                    r = readCacheOrNull(key, type);
                    if (r != null) return r;             // 别人建好了，直接用
                }
            }
            try {
                r = readCacheOrNull(key, type);          // ⭐双重检查
                if (r != null) return r;
                r = dbFallback.apply(id);
                if (r == null) {
                    redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL_MINUTES, TimeUnit.MINUTES);
                    return null;
                }
                this.set(key, r, time, unit);
                return r;
            } finally {
                unlock(lockKey);                         // ⭐必须 finally——异常不释放=死锁
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("缓存查询被中断", e);
        }
    }

    // ───────────────────────── 击穿防护版二：逻辑过期 ─────────────────────────

    /**
     * 查询（防击穿·逻辑过期版）：key 物理永不过期，过期与否看 RedisData.expireAt。
     *  - 未过期：直接返回（连锁都不碰）
     *  - 已过期：抢到锁的线程丢给线程池【异步重建】，自己（和所有等待者）立刻返回旧值
     * ⭐trade-off：用短暂的不一致换零等待——导购页显示旧营业时间 vs 页面转圈 3 秒，业务上前者更能接受
     * ⭐前提：热点数据要提前预热进 Redis（见 VenueService.preload），否则第一波请求缓存里没有
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return null;                                 // 未预热：调用方自行兜底查库+预热
        }
        RedisData rd = JsonUtils.parse(json, RedisData.class);
        if (rd.getExpireAt() == null || rd.getExpireAt() > System.currentTimeMillis()) {
            return JsonUtils.MAPPER.convertValue(rd.getData(), type);   // 未过期，直接回
        }
        String lockKey = LOCK_PREFIX + key;
        if (tryLock(lockKey, 10)) {                      // 过期：抢锁触发异步重建
            REBUILD_POOL.submit(() -> {
                try {
                    R fresh = dbFallback.apply(id);
                    if (fresh != null) {
                        setWithLogicalExpire(key, fresh, time, unit);
                    }
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 抢没抢到锁都返回旧数据——高可用优先
        return JsonUtils.MAPPER.convertValue(rd.getData(), type);
    }

    // ───────────────────────── 内部小工具 ─────────────────────────

    private <R> R readCacheOrNull(String key, Class<R> type) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) return null;
        return JsonUtils.parse(json, type);
    }

    private boolean isNullMarker(String key) {
        String json = redisTemplate.opsForValue().get(key);
        return json != null && json.isEmpty();
    }

    /** 最简 SETNX 锁。⭐锁自身的演进链（裸锁→TTL→UUID+Lua→Redisson）见 SimpleRedisLock */
    private boolean tryLock(String key, long timeoutSec) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(key, "1", timeoutSec, TimeUnit.SECONDS));
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }
}
