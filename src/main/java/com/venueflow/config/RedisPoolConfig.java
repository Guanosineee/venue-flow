package com.venueflow.config;

import io.lettuce.core.api.StatefulConnection;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

/**
 * Redis 连接池配置——性能实验 v2，附完整实验记录（含一次被证伪的归因）。
 *
 * ───────────────────── v1 基线（默认配置）的异常形态 ─────────────────────
 * Lettuce 默认 shareNativeConnection=true：普通命令共享一条 TCP 连接。
 * 实测（/venue/1 缓存命中，keep-alive 压测）：8/16/24 线程 = 4974/5189/5253 QPS，
 * 并发翻 3 倍 QPS 只 +5%、P99 线性恶化 ×3，而 Java 仅 3.7/16 核（23%）——
 * CPU 未饱和吞吐先到顶。初始假设：瓶颈=单连接串行往返。
 *
 * ───────────────────────── v2：关共享 + 开池（本类） ─────────────────────────
 * 实测 5223 QPS（24T）——【假设证伪】，池确认生效（压测中 connected_clients=50）。
 * 后续排除法：
 *   v3 绕过 wslrelay 直连 WSL IP：5522（+5%）——排除端口转发层
 *   v4 4 个独立压测进程（4 把 GIL）合计 11800——【真凶=单进程客户端 GIL】，
 *      服务端从未被压满；v5 8 进程合计 12970，单进程吞吐腰斩——第二层瓶颈浮现
 *      （嫌疑 Redis 单线程/docker-proxy，未完全定位，诚实记录）。
 *
 * 池配置保留理由：客户端 GIL 是【本机压测方法论缺陷】而非服务端结论——
 * 生产真实并发来自多个连接/多台机器，多连接池化依然是正确的工程实践。
 * ⭐最大教训（比结论更值钱）：压测数字 = 客户端 × 网络 × 服务端的乘积，
 *   每一层都可能当瓶颈——先证明客户端没饱和，再谈服务端优化。
 */
@Configuration
public class RedisPoolConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig(properties.getLettuce().getPool()))
                .commandTimeout(properties.getTimeout() != null
                        ? properties.getTimeout() : Duration.ofSeconds(3))
                .build();
        RedisStandaloneConfiguration serverConfig =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.setShareNativeConnection(false);   // v1→v2 的那一行主菜
        return factory;
    }

    private GenericObjectPoolConfig<StatefulConnection> poolConfig(RedisProperties.Pool pool) {
        GenericObjectPoolConfig<StatefulConnection> config = new GenericObjectPoolConfig<>();
        if (pool != null) {
            config.setMaxTotal(pool.getMaxActive());
            config.setMaxIdle(pool.getMaxIdle());
            config.setMinIdle(pool.getMinIdle());
            if (pool.getMaxWait() != null) {
                config.setMaxWait(Duration.ofMillis(pool.getMaxWait().toMillis()));
            }
        }
        return config;
    }
}
