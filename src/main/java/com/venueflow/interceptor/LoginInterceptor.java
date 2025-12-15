package com.venueflow.interceptor;

import com.venueflow.dto.Result;
import com.venueflow.utils.JsonUtils;
import com.venueflow.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 第二道拦截器（order=1，只挂敏感路径）：真正执行"强制登录"。
 * 用户信息已由第一道放进 ThreadLocal，这里只判空。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        if (UserHolder.get() != null) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonUtils.toJSON(Result.fail("未登录")));
        return false;
    }
}
