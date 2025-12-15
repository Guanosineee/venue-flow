package com.venueflow.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 手写分布式锁（教学完整版）——一条"被迫进化"的演进链，⭐⭐必背：
 *
 * v1  SETNX lock 1（无过期时间）
 *     → 拿锁的线程宕机，锁永远在 → 死锁。教训：SETNX 必须带 TTL
 * v2  SET lock 1 NX EX 10
 *     → 业务没跑完锁先过期，B 拿到锁；A 回头执行 del → 删的是【B 的锁】！
 *     教训：删锁前得确认是自己的锁
 * v3  值放 UUID+线程id（标识持有者）+ 删锁前 GET 比对
 *     → GET 和 DEL 是两步，比对通过后锁恰好过期、B 又进来了 → 还是可能删错。
 *     教训：校验+删除必须【原子】→ Lua 脚本（本类实现的就是 v3）
 * v4  手写版仍有三宗罪：不可重入、无重试机制、业务超时不能续期
 *     → Redisson：Hash 结构记重入次数、看门狗默认每 10s 续期到 30s、
 *       Pub/Sub 通知唤醒重试。生产直接用（本项目 TicketOrderService 有 A/B 位）
 *
 * ⭐延伸：Redis 挂了锁怎么办 → Redisson 红锁(RedLock)多主节点过半数；主从切换锁丢失 → 看门狗+兜底。
 * 更重的场景用 ZooKeeper/etcd 临时顺序节点（CP），Redis 方案是 AP 取舍——能聊到这层就是满分区间
 */
public class SimpleRedisLock {

    private static final String KEY_PREFIX = "lock:";
    /** 进程级标识：同一 JVM 内所有锁共用一个 UUID，再用线程 id 区分持有者 */
    private static final String ID_PREFIX = UUID.randomUUID().toString().replace("-", "");

    /** 只删自己的锁：GET 比对 + DEL 两步合成一个原子操作（Lua 在 Redis 单线程内执行，天然原子） */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        String lua = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                else
                    return 0
                end
                """;
        UNLOCK_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
    }

    private final String name;
    private final StringRedisTemplate redisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    /** 锁的持有者标识：进程 UUID + 线程 id（线程池线程复用，必须每次调用时现取，不能静态缓存） */
    private String threadFlag() {
        return ID_PREFIX + "-" + Thread.currentThread().threadId();
    }

    public boolean tryLock(long timeoutSec) {
        // SET NX EX 一条命令原子完成"不存在才设置+过期时间"——v2 教训的落地
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadFlag(), timeoutSec, TimeUnit.SECONDS));
    }

    public void unlock() {
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(KEY_PREFIX + name), threadFlag());
    }
}
