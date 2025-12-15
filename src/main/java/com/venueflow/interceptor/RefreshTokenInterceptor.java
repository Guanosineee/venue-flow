package com.venueflow.interceptor;

import com.venueflow.dto.UserDTO;
import com.venueflow.utils.JsonUtils;
import com.venueflow.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 第一道拦截器（/** ）：只做"识别用户+滑动续期"，永不拦截。
 *
 * ⭐面试点：为什么要两道拦截器？
 *  - 续期要对【所有】已登录用户生效（哪怕他访问的是公开接口，活跃就该续命）
 *  - 强制登录只对敏感接口生效
 *  - 一道拦截器没法表达"只续期不拦"和"拦"两种策略 → 拆两道，第一道 order=0 全量放行
 *
 * ⭐面试点：会话为什么放 Redis 不用 HttpSession / 不用 JWT？
 *  - HttpSession 存在 Tomcat 内存：多实例部署不共享（负载均衡第二台就找不着会话）
 *  - Redis 集中存储：无状态水平扩展、天然共享、还能设置 TTL 自动过期
 *  - JWT：无状态但【登出即失效/续期/踢人】很难做（签名固定），有状态 Redis 反而好管
 */
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    public static final String TOKEN_KEY_PREFIX = "login:token:";

    private final StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String token = request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return true;   // 没有 token 也放行——是否需要登录由第二道决定
        }
        String json = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (json == null) {
            return true;   // token 无效/过期同样放行
        }
        UserHolder.set(JsonUtils.parse(json, UserDTO.class));
        // 滑动续期：只要用户还在活跃，会话就不过期（30 分钟）
        redisTemplate.opsForValue().getAndExpire(TOKEN_KEY_PREFIX + token, 30, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        UserHolder.remove();   // ⭐ThreadLocal 必清：线程池线程复用，不清=下个请求读到上个用户（串号）
    }
}
