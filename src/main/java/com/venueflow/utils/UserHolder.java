package com.venueflow.utils;

import com.venueflow.dto.UserDTO;

/**
 * 基于 ThreadLocal 的登录用户传递。
 * ⭐面试点：为什么用 ThreadLocal——一次请求绑定一个线程，Controller/Service 任意层
 * 都能拿到当前用户，不用层层传参；本质是"线程私有储物柜"。
 * ⭐配套考点（必背泄漏链）：线程池的线程不销毁 → ThreadLocalMap 的 key 是弱引用
 * 会被 GC，value 是强引用滞留 → 用完必须在 finally / afterCompletion 里 remove()
 */
public final class UserHolder {
    private static final ThreadLocal<UserDTO> TL = new ThreadLocal<>();

    private UserHolder() {}

    public static void set(UserDTO user) { TL.set(user); }
    public static UserDTO get() { return TL.get(); }
    public static void remove() { TL.remove(); }
}
