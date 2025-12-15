package com.venueflow.service;

import com.venueflow.entity.Venue;
import com.venueflow.mapper.VenueMapper;
import com.venueflow.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 展馆服务：缓存三兄弟 + GEO 附近展馆。
 *
 * ⭐面试点：缓存模式可以配置切换（venue-flow.cache-mode）——
 *  pass-through / mutex / logical-expire 三挡跑同一压测脚本对比延迟与 DB 压力，
 *  "我用数据选了策略"比"我背了策略"可信十倍。
 */
@Slf4j
@Service
public class VenueService {

    public static final String CACHE_KEY_PREFIX = "cache:venue:";
    public static final String GEO_KEY = "venue:geo";
    private static final long CACHE_TTL_MINUTES = 30;

    private final VenueMapper venueMapper;
    private final CacheClient cacheClient;
    private final StringRedisTemplate redisTemplate;

    @Value("${venue-flow.cache-mode}")
    private String cacheMode;

    public VenueService(VenueMapper venueMapper, CacheClient cacheClient, StringRedisTemplate redisTemplate) {
        this.venueMapper = venueMapper;
        this.cacheClient = cacheClient;
        this.redisTemplate = redisTemplate;
    }

    /** 展馆详情：按配置走三种缓存策略之一（30 分钟 TTL，set 内部已加随机抖动防雪崩） */
    public Venue queryById(Long id) {
        return switch (cacheMode) {
            case "mutex" -> cacheClient.queryWithMutex(
                    CACHE_KEY_PREFIX, id, Venue.class, this::queryDb, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            case "logical-expire" -> {
                Venue v = cacheClient.queryWithLogicalExpire(
                        CACHE_KEY_PREFIX, id, Venue.class, this::queryDb, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                if (v == null) {   // 未预热：开发兜底（生产应提前预热热门展馆）
                    v = queryDb(id);
                    if (v != null) {
                        cacheClient.setWithLogicalExpire(CACHE_KEY_PREFIX + id, v, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                    }
                }
                yield v;
            }
            default -> cacheClient.queryWithPassThrough(
                    CACHE_KEY_PREFIX, id, Venue.class, this::queryDb, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        };
    }

    private Venue queryDb(Long id) {
        return venueMapper.selectById(id);
    }

    /** 逻辑过期模式的预热入口：压测前把热点展馆塞进缓存 */
    public void preload(Long id) {
        Venue v = queryDb(id);
        if (v != null) {
            cacheClient.setWithLogicalExpire(CACHE_KEY_PREFIX + id, v, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
        }
    }

    /** 清缓存（演示 Cache-Aside：改库后删缓存，下次查询自然回源重建） */
    public void evict(Long id) {
        redisTemplate.delete(CACHE_KEY_PREFIX + id);
    }

    // ───────────────────────── GEO：附近展馆 ─────────────────────────

    /**
     * 全量展馆坐标载入 Redis GEO（GEO 底层 = 有序集合 + GeoHash 编码的 52bit 分数）。
     * ⭐面试点：为什么不用 SQL "经纬度距离公式"？——计算全表扫描，数据量大时扛不住；
     * GEO 底层 GeoHash 把二维坐标编码成一维前缀，相邻点前缀相近 → 有序集合高效范围查。
     */
    public void loadGeo() {
        List<Venue> all = venueMapper.selectList(null);
        all.forEach(v -> redisTemplate.opsForGeo()
                .add(GEO_KEY, new Point(v.getLon(), v.getLat()), v.getId().toString()));
    }

    /** 附近展馆：GEOSEARCH + 距离升序，返回展馆详情+距离（km） */
    public List<Map<String, Object>> nearby(double lon, double lat, double radiusKm) {
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(
                GEO_KEY,
                GeoReference.fromCoordinate(lon, lat),
                new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance().sortAscending().limit(10));
        if (results == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Map<String, Double> distMap = new HashMap<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> r : results.getContent()) {
            String id = r.getContent().getName();
            ids.add(id);
            distMap.put(id, r.getDistance().getValue());
        }
        List<Long> longIds = ids.stream().map(Long::parseLong).collect(Collectors.toList());
        Map<Long, Venue> venueMap = venueMapper.selectBatchIds(longIds).stream()
                .collect(Collectors.toMap(Venue::getId, v -> v));
        return ids.stream()
                .filter(id -> venueMap.containsKey(Long.parseLong(id)))
                .map(id -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("venue", venueMap.get(Long.parseLong(id)));
                    m.put("distanceKm", distMap.get(id));
                    return m;
                })
                .collect(Collectors.toList());
    }
}
