# 展遇 · 场馆文旅服务平台

> 个人项目 · Spring Boot 3 (JDK 21) + Redis + RabbitMQ + MySQL。
> 业务场景：热门特展放票即秒杀洪峰，展馆详情页是典型的高并发读，观展社区承载互动与统计。
> 核心：秒杀链路的并发正确性、缓存三策略的对比选型、按读写形态选型的 Redis 数据结构实践。

## 1. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 框架 | Spring Boot 3.2 / JDK 21 | |
| 缓存 | Spring Data Redis + Redisson | StringRedisTemplate 手写全部结构操作 |
| 消息 | RabbitMQ（Topic 交换机） | 秒杀异步下单削峰 |
| 存储 | MySQL 8 + MyBatis-Plus | |
| 压测 | Python 标准库脚本（scripts/） | 零依赖，结果可复现 |

## 2. 架构（秒杀主链）

```
用户 ──token──► 拦截器(识别+续期) ──► OrderController
                                          │
                                          ▼
                              ①Lua 原子判定（Redis，μs级）
                                 库存够？没抢过？→ decr + sadd
                                          │ 判定通过
                                          ▼
                              ②RabbitMQ order.topic ──► 立即返回订单号
                                          │
                                          ▼
                              ③消费者落库（DB 事务）
                                 条件更新 stock>0 + 插订单
                                          │
                                          ▼
                              ④uk_user_ticket 唯一索引兜底（幂等最后一道墙）
```

防超卖四层：Lua 原子判定 → MQ 缓冲 → 条件更新（`UPDATE ... WHERE stock>0`）→ 唯一索引。前一层被击穿后一层接着扛。

## 3. 快速开始

```bash
# 1. 拉起中间件（首次自动执行 sql/init.sql 建库造数）
docker compose up -d

# 2. 启动应用
mvn spring-boot:run

# 3. 冒烟
curl "http://localhost:8080/venue/1"
curl -X POST "http://localhost:8080/venue/geo/load"
curl "http://localhost:8080/venue/nearby?lon=116.40&lat=39.90&radius=5"

# 4. 压测准备：application.yml 把 venue-flow.unsafe 改为 true 重启，生成 token
cd scripts
python gen_tokens.py --n 200 --out tokens.txt
```

RabbitMQ 控制台 http://localhost:15672（guest/guest）。Redis：`docker exec -it venue-flow-redis redis-cli`。
端口冲突时（如本机 3306 被占用）改 docker-compose 映射并同步修改 application.yml。

## 4. 接口清单

| 方法 | 路径 | 说明 | 登录 |
|---|---|---|---|
| POST | /user/code?phone= | 发验证码（unsafe 模式回显） | ✗ |
| POST | /user/login?phone=&code= | 登录即注册，返回 token | ✗ |
| GET | /user/me | 当前用户 | ✓ |
| GET | /venue/{id} | 展馆详情（缓存策略三挡可切） | ✗ |
| GET | /venue/nearby?lon=&lat=&radius= | 附近展馆（GEO） | ✗ |
| POST | /venue/geo/load | 坐标载入 GEO | ✗ |
| POST | /venue/preload/{id} | 逻辑过期预热 | ✗ |
| DELETE | /venue/cache/{id} | 清缓存（Cache-Aside 演示） | ✗ |
| POST | /ticket/seckill | 创建秒杀票 | ✗ |
| POST | /ticket/reset?ticketId=&stock= | 压测重置 | ✗ |
| POST | /order/seckill/{ticketId} | **秒杀下单（正式版）** | ✓ |
| POST | /order/seckill-naive/{ticketId} | **无保护对照版**（unsafe 开关） | ✓ |
| GET | /order/stat/{ticketId} | 对账：DB/Redis 库存+订单数 | ✗ |
| PUT | /blog/like/{id} | 点赞/取消（ZSET） | ✓ |
| GET | /blog/likes/{id} | 点赞排行前五 | ✗ |
| GET | /blog/hot | 热门展评 | ✗ |
| PUT | /follow/{id}/{isFollow} | 关注/取关（SET） | ✓ |
| GET | /follow/common/{id} | 共同关注（SINTER） | ✓ |
| POST | /stats/visit | 访问记录（PFADD） | ✓ |
| GET | /stats/uv | 当日 UV | ✗ |
| POST | /stats/sign | 签到（bitmap） | ✓ |
| GET | /stats/sign/count | 连续签到天数 | ✗ |

## 5. 实验设计

本项目保留了一个**无保护对照实现**（`/order/seckill-naive`，配置开关控制），用于复现并发缺陷；正式链路在此基础上逐层加固。同一压测脚本（200 并发用户抢 100 张票）对两个版本各跑一轮，结果如下：

| 版本 | 下单成功 | 库存终态 | 超卖 |
|---|---|---|---|
| 无保护对照版 | 108 单 | dbStock = **-9** | **是** |
| 正式版 | **精确 100 单** | redisStock=0，DB 数秒内对齐至 100/0 | **否** |

正式版的"DB 数秒内对齐"即 MQ 异步落库的最终一致性窗口：Redis 判定层即时收口，DB 账本按消费节奏追平。

缓存实验：`python bench.py venue --id 1` 输出冷回源（清缓存后首轮）与热命中延迟对比；`venue-flow.cache-mode` 在 pass-through / mutex / logical-expire 三挡间切换，可对同一接口做策略对比。

> 测量口径：本机（8C16T）、200 Python 线程客户端、`spring-boot:run`。吞吐 ~69 req/s 的瓶颈在压测客户端与运行模式，不在被测方案；对照实验的有效性来自**同流量同脚本下的组间差异**。

冷/热缓存延迟（本机小库，量级供参考）：冷回源 avg 2.7ms / P95 3.8ms → 热命中 avg 1.8ms / P95 2.1ms。

饱和吞吐与瓶颈排查实验（`bench.py qps`，keep-alive 持续压测，同机）：

| # | 配置 | 客户端 | QPS | 结论 |
|---|---|---|---|---|
| v1 | 默认（共享单连接） | 1 进程×24T | 5253 | 并发翻 3 倍 QPS 只 +5%；CPU 仅 23% |
| v2 | Lettuce 池（32 连接） | 1 进程×24T | 5223 | 池生效（connected=50）但**假设证伪** |
| v3 | 直连 WSL（绕 wslrelay） | 1 进程×24T | 5522 | +5%，排除端口转发层 |
| v4 | 池 + 直连 | **4 进程**×6T | **合计 11800** | **真凶=单进程客户端 GIL**，服务端从未压满 |
| v5 | 池 + 直连 | 8 进程×6T | 合计 12970 | 单进程吞吐腰斩，第二层瓶颈浮现（未定位完，诚实记录） |

写路径对照：秒杀售罄快速路径 2339 QPS（1 进程×8T）——比读路径腰斩，归因=异常栈开销+拦截器多一次 Redis 往返。

方法论沉淀：压测数字 = **客户端 × 网络 × 服务端** 的乘积，任何一层都可能当瓶颈——CPU 未饱和却到顶时先怀疑串行点（连接/锁/转发层/**压测客户端自己的 GIL**）；确认客户端饱和前，不要急着优化服务端。Little's Law 自检：并发 = QPS × 平均延迟（16 ≈ 5189 × 3.1ms ✓）。

## 6. 设计决策记录

| 问题 | 决策 | 依据 |
|---|---|---|
| 缓存穿透 | 空值缓存 + 短 TTL（2min） | 让"不存在"也成为缓存值；布隆过滤器作为进阶备选（误判率/删除难） |
| 缓存击穿 | 互斥锁与逻辑过期**双实现**，配置切换 | 强一致（等待）与高可用（脏读窗口）的权衡交给实验数据：同脚本压测对比 |
| 缓存雪崩 | TTL 随机抖动 [T, 2T) | 打散同时过期；集群高可用与限流降级为架构层兜底 |
| 超卖 | 判定前移：库存与一人一单校验合并为单个 Lua 脚本 | 查-判-扣三步非原子是超卖根因；Redis 单线程执行脚本天然原子 |
| 落库压力 | MQ 异步下单 | 判定通过即回订单号；DB 按自身节奏消费，洪峰被拉平 |
| 消息重复投递 | 消费端两层幂等（查询 + 唯一索引） | 重复投递无法根除，账本层唯一索引是最后防线 |
| 分布式 ID | 31bit 时间戳 + 32bit Redis 序列 | 趋势递增（B+ 树友好）；UUID 无序致页分裂，自增在分库分表下撞号 |
| 分布式锁 | 手写 SETNX→UUID+Lua 原子释放→Redisson 三阶段演进并保留 A/B 位 | 演进链即踩坑史：无 TTL 死锁、锁过期误删、校验删除非原子 |
| 会话 | Redis token 会话（30min 滑动续期） | HttpSession 多实例不共享；JWT 主动失效难 |
| 点赞排行 | ZSET（score=时间戳） | 排行需求要"谁+何时"，SET 只能去重 |
| 访客统计 | HyperLogLog（12KB / 0.81% 误差）与 bitmap（1bit/天）分场景 | 要"大约多少"用 HLL，要"具体谁在何时"用 bitmap |

## 7. 目录结构

```
venue-flow/
├── docker-compose.yml        # mysql + redis + rabbitmq 一键拉起
├── sql/init.sql              # 建库建表 + 12 展馆 + 2 秒杀票种子数据
├── scripts/
│   ├── gen_tokens.py         # 批量造用户 token（压测前置）
│   └── bench.py              # 秒杀压测+对账 / 缓存冷热对比（支持 --naive 打对照版）
└── src/main/java/com/venueflow/
    ├── config/               # RabbitConfig 拓扑 / 拦截器注册 / 启动库存预热 / 全局异常
    ├── interceptor/          # 两道拦截器（续期+强制登录）
    ├── utils/                # CacheClient 缓存三策略 / SimpleRedisLock 锁演进 /
    │                         # RedisIdWorker / UserHolder(ThreadLocal) / JsonUtils
    ├── service/              # TicketOrderService 秒杀四层 / VenueService 缓存+GEO /
    │                         # UserService 会话 / Blog / Follow / Stats
    ├── mq/                   # 订单消息消费者（幂等落库）
    ├── controller/           # 七个薄控制器
    ├── entity/ mapper/ dto/
    └── VenueFlowApplication.java
```
