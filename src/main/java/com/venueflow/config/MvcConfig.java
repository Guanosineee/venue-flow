package com.venueflow.config;

import com.venueflow.interceptor.LoginInterceptor;
import com.venueflow.interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册：两道拦截器按 order 分层挂载。
 * 第一道全量（续期+传用户），第二道只挂敏感路径（强制登录）。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    private final RefreshTokenInterceptor refreshTokenInterceptor;
    private final LoginInterceptor loginInterceptor;

    public MvcConfig(RefreshTokenInterceptor refreshTokenInterceptor, LoginInterceptor loginInterceptor) {
        this.refreshTokenInterceptor = refreshTokenInterceptor;
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                        "/user/code", "/user/login",   // 登录本身
                        "/venue/**",                   // 展馆浏览公开
                        "/blog/hot",                   // 热榜公开
                        "/blog/likes/**",              // 点赞排行公开（只读）
                        "/stats/uv",                   // UV 统计公开
                        "/order/stat/**",              // 压测对账接口公开（只读观测）
                        "/ticket/**",                  // 票务查询/压测重置公开（创建票为本地管理操作）
                        "/error")
                .order(1);
    }
}
