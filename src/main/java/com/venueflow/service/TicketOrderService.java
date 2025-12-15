package com.venueflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.venueflow.dto.SeckillMessage;
import com.venueflow.entity.Ticket;
import com.venueflow.entity.TicketOrder;
import com.venueflow.mapper.TicketMapper;
import com.venueflow.mapper.TicketOrderMapper;
import com.venueflow.utils.RedisIdWorker;
import com.venueflow.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀下单——整个项目被拷打概率最高的类，四层结构背熟：
 *
 *   请求 → ①Lua 原子判定(Redis) → ②发 MQ 立即回订单号 → ③消费者落库(DB事务) → ④唯一键兜底
 *
 * ───────────────────── ⭐演进叙事（面试讲项目难点的现成剧本） ─────────────────────
 * v0 朴素版（naiveSeckill，本类保留用于压测复现超卖）：查库存→判断→扣减，三步非原子。
 *    压测亲测：并发下两人同时读到 stock=1，都通过判断，都扣减 → 卖出 101 单，库存 -1
 * v1 一人一单：加"查该用户订单数"判断 + synchronized / 分布式锁（SimpleRedisLock）——
 *    单体能跑，但锁粒度锁到整个方法，吞吐被锁住；锁的演进链看 SimpleRedisLock 的 javadoc
 * v2 判定前移进 Redis + Lua：库存判断+一人一单判断+扣减+记录四步合成【一个 Lua 脚本】，
 *    Redis 单线程执行脚本天然原子——判定吞吐从"DB 行锁几百"提到"内存 10w+"
 * v3 落库异步化：判定通过发 MQ 就返回订单号，DB 按自己的节奏消费——削峰填谷
 * 终 DB 侧仍保留两道防线：条件更新(stock>0) + 唯一键 uk_user_ticket——
 *    即使 Redis 和 DB 短暂不一致 / 消息重复投递，账本层面绝不多卖一人一单
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Service
public class TicketOrderService {

    /**
     * ⭐核心 Lua：四步一个原子操作。
     * 为什么必须是 Lua？——"查库存→判断→扣减→记用户"拆成四条 Redis 命令的话，
     * 命令之间能插进别的请求（和 DB 的查-判-扣一样会超卖）；Lua 脚本在 Redis 里
     * 一次性执行完，中间无人插队。KEYS/ARGV 分开传是 Redis 规范（利于集群定位 key）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        String lua = """
                -- 1. 库存不足，返回 1
                local stock = tonumber(redis.call('get', KEYS[1]))
                if stock == nil or stock <= 0 then
                    return 1
                end
                -- 2. 该用户已抢过（一人一单），返回 2
                if redis.call('sismember', KEYS[2], ARGV[1]) == 1 then
                    return 2
                end
                -- 3. 扣库存 + 记录用户，返回 0（成功）
                redis.call('decr', KEYS[1])
                redis.call('sadd', KEYS[2], ARGV[1])
                return 0
                """;
        SECKILL_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
    }

    private final TicketOrderMapper orderMapper;
    private final TicketMapper ticketMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisIdWorker idWorker;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;

    @Value("${venue-flow.unsafe:false}")
    private boolean unsafe;

    public TicketOrderService(TicketOrderMapper orderMapper, TicketMapper ticketMapper,
                              StringRedisTemplate redisTemplate, RedisIdWorker idWorker,
                              RabbitTemplate rabbitTemplate, RedissonClient redissonClient) {
        this.orderMapper = orderMapper;
        this.ticketMapper = ticketMapper;
        this.redisTemplate = redisTemplate;
        this.idWorker = idWorker;
        this.rabbitTemplate = rabbitTemplate;
        this.redissonClient = redissonClient;
    }

    // ───────────────────── 正式版：Lua 判定 + MQ 异步落库 ─────────────────────

    /**
     * 秒杀下单（在线路径只碰 Redis 和 MQ，DB 一点不碰——这是能扛住洪峰的全部秘密）。
     * 全链路：Lua 判定(μs 级) → 发消息 → 返回订单号。用户拿着订单号稍后查单。
     */
    public Long seckill(Long ticketId) {
        Long userId = UserHolder.get().getId();
        Long orderId = idWorker.nextId("order");   // 订单号提前生成：MQ 异步链路各环节要靠它对齐

        Long r = redisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(TicketService.STOCK_KEY_PREFIX + ticketId,
                        TicketService.USER_SET_KEY_PREFIX + ticketId),
                userId.toString());

        if (r == null || r != 0) {
            throw new RuntimeException(r != null && r == 2 ? "您已抢过该门票" : "门票已售罄");
        }

        rabbitTemplate.convertAndSend(
                com.venueflow.config.RabbitConfig.ORDER_EXCHANGE,
                com.venueflow.config.RabbitConfig.ORDER_ROUTING_KEY,
                new SeckillMessage(ticketId, userId, orderId));
        return orderId;
    }

    /** MQ 消费者调用的落库方法（DB 事务内） */
    @Transactional
    public void createSeckillOrder(SeckillMessage msg) {
        // 幂等第一层：消息重复投递时直接吞掉
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<TicketOrder>()
                .eq(TicketOrder::getUserId, msg.userId())
                .eq(TicketOrder::getTicketId, msg.ticketId()));
        if (count > 0) {
            return;
        }
        Ticket ticket = ticketMapper.selectById(msg.ticketId());
        if (ticket == null) {
            return;
        }
        // ⭐防超卖的 DB 侧防线：条件更新——"扣到 0 以下"这个条件本身写进 UPDATE 的 WHERE，
        // 一条 SQL 原子完成"判+扣"，不给并发留缝隙（InnoDB 行锁保证同一行的 UPDATE 串行）
        int updated = ticketMapper.update(null, Wrappers.<Ticket>update()
                .setSql("stock = stock - 1")
                .eq("id", msg.ticketId())
                .gt("stock", 0));
        if (updated == 0) {
            return;   // DB 侧库存已清零：Redis 允许的请求量 >= DB 库存的极端场景，在这里对齐账本
        }
        TicketOrder order = new TicketOrder();
        order.setId(msg.orderId());
        order.setUserId(msg.userId());
        order.setTicketId(msg.ticketId());
        order.setPayAmount(ticket.getPayAmount());
        order.setCreateTime(LocalDateTime.now());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // ⭐幂等最后一道墙：uk_user_ticket 唯一键。检查被并发绕过/消息重复，都撞在这里。
            // 注意不抛异常——抛了消息会无限重投
        }
    }

    // ───────────────────── 翻车版：压测复现超卖（unsafe 开关） ─────────────────────

    /**
     * 教科书错误示范：保留它是本项目的"实验设计"——先跑这版亲眼看见超卖，
     * 再跑正式版对比，防超卖方案才不是背出来的。
     * 错在哪：读库存(1) → 判断(2) → 扣减(3) 三步之间可插入并发，
     * 两个请求都执行到 (2) 时看到的都是 stock=1，双双通过，双双扣减 → 超卖
     */
    @Transactional
    public Long naiveSeckill(Long ticketId) {
        Long userId = UserHolder.get().getId();
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getStock() <= 0) {
            throw new RuntimeException("门票已售罄");
        }
        // 注意：无条件扣减（没有 stock > 0 的 WHERE）——超卖的直接现场
        ticketMapper.update(null, Wrappers.<Ticket>update()
                .setSql("stock = stock - 1")
                .eq("id", ticketId));
        TicketOrder order = new TicketOrder();
        order.setId(idWorker.nextId("order"));
        order.setUserId(userId);
        order.setTicketId(ticketId);
        order.setPayAmount(ticket.getPayAmount());
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);
        return order.getId();
    }

    // ───────────────────── A/B 实验位：手写锁 vs Redisson ─────────────────────

    /**
     * v1 时代的锁版下单（现在只作对比实验，不进主链路）。
     * ⭐被问"为什么后来不用锁了"：锁的正确姿势是【只锁一人一单的判定】，
     * 但 v1 把整个下单事务都锁住——锁粒度过大，吞吐被锁死；
     * v2 把判定挪进 Lua 之后，"锁"这个概念其实升级成了"原子脚本"，锁就退场了。
     *
     * 手写锁版：
     *   SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, redisTemplate);
     *   if (!lock.tryLock(5)) return;   // 没抢到锁=重复点击，直接失败
     *   try { ...查单+扣库存+插单... } finally { lock.unlock(); }
     *
     * Redisson 版（三行换掉手写锁，看门狗自动续期，不用赌超时时间）：
     *   RLock lock = redissonClient.getLock("lock:order:" + userId);
     *   boolean ok = lock.tryLock(0, TimeUnit.SECONDS);   // waitTime=0: 抢不到立刻失败
     *   if (!ok) return;
     *   try { ... } finally { lock.unlock(); }            // 看门狗默认把持有期续到 30s
     */
    public void lockExperiment(Long userId, Runnable body) {
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, TimeUnit.SECONDS);   // waitTime=0：抢不到立刻失败（重复点击场景）
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    // ───────────────────── 压测观测接口 ─────────────────────

    /** 对账用：一次看到 DB 库存 / Redis 库存 / 订单数（压测后跑一眼，超卖/对账一目了然） */
    public Map<String, Object> stat(Long ticketId) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        String redisStock = redisTemplate.opsForValue().get(TicketService.STOCK_KEY_PREFIX + ticketId);
        Long orderCount = orderMapper.selectCount(
                new LambdaQueryWrapper<TicketOrder>().eq(TicketOrder::getTicketId, ticketId));
        Map<String, Object> m = new HashMap<>();
        m.put("ticketId", ticketId);
        m.put("dbStock", ticket == null ? null : ticket.getStock());
        m.put("redisStock", redisStock == null ? null : Long.parseLong(redisStock));
        m.put("orderCount", orderCount);
        m.put("oversold", ticket != null && ticket.getStock() < 0);   // 翻车版的铁证
        return m;
    }
}
