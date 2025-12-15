package com.venueflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.venueflow.dto.UserDTO;
import com.venueflow.entity.User;
import com.venueflow.mapper.UserMapper;
import com.venueflow.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 短信验证码登录 + Redis 会话。
 *
 * ⭐面试点：登录为什么用"手机验证码"而不是"账号密码"？
 *  展馆场景游客即来即用，注册门槛必须为零——输手机号收码即登录，注册登录合一。
 * ⭐面试点：会话流程（口述要顺）：
 *  发码(login:code:手机号, 2min) → 校验 → 查库(无则自动注册) → 生成随机 token
 *  → 会话存 Redis(login:token:token → UserDTO JSON, 30min) → token 返回给前端
 *  → 之后每次请求带 authorization 头 → 拦截器识别+续期（见 RefreshTokenInterceptor）
 */
@Slf4j
@Service
public class UserService {

    private static final String CODE_KEY_PREFIX = "login:code:";
    private static final long CODE_TTL_MINUTES = 2;

    private final StringRedisTemplate redisTemplate;
    private final UserMapper userMapper;

    @Value("${venue-flow.unsafe:false}")
    private boolean unsafe;   // 压测模式：验证码回显给脚本

    public UserService(StringRedisTemplate redisTemplate, UserMapper userMapper) {
        this.redisTemplate = redisTemplate;
        this.userMapper = userMapper;
    }

    /**
     * 发送验证码。
     * ⭐生产形态：真实短信走第三方（阿里云 SMS），发送动作丢 MQ 异步（用户不用等短信网关），
     * 同一手机号限流（如 1 分钟 1 条/天 10 条，防短信轰炸薅羊毛）。本项目用日志模拟。
     */
    public String sendCode(String phone) {
        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + phone, code, CODE_TTL_MINUTES, TimeUnit.MINUTES);
        log.info("【模拟短信】{} 的验证码：{}", phone, code);
        return unsafe ? code : null;
    }

    /**
     * 校验验证码并登录（登录即注册）。
     * ⭐注意验证码校验用 Redis 比对而非任何本地状态——多实例部署下任意机器都能校验。
     */
    @Transactional
    public String login(String phone, String code) {
        String cache = redisTemplate.opsForValue().get(CODE_KEY_PREFIX + phone);
        if (cache == null || !cache.equals(code)) {
            throw new RuntimeException("验证码错误或已过期");
        }
        redisTemplate.delete(CODE_KEY_PREFIX + phone);   // 验证码一次性，防重放

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {   // 首次登录 = 自动注册
            user = new User();
            user.setPhone(phone);
            user.setNickName("游客" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)));
            userMapper.insert(user);
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        UserDTO dto = new UserDTO(user.getId(), user.getNickName());
        redisTemplate.opsForValue().set(
                com.venueflow.interceptor.RefreshTokenInterceptor.TOKEN_KEY_PREFIX + token,
                JsonUtils.toJSON(dto), 30, TimeUnit.MINUTES);
        return token;
    }

    public UserDTO me() {
        UserDTO user = com.venueflow.utils.UserHolder.get();
        if (user == null) {
            throw new RuntimeException("未登录");
        }
        return user;
    }
}
