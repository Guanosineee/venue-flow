package com.venueflow.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

// 全局共享一个 ObjectMapper（线程安全，创建开销大）。
// ⭐小八股：ObjectMapper 是重对象，static 单例是标准做法
// findAndRegisterModules：让 LocalDateTime 等 Java8 时间类型可序列化（jsr310 模块）
public final class JsonUtils {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {}

    public static String toJSON(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { throw new RuntimeException("json序列化失败", e); }
    }

    public static <T> T parse(String json, Class<T> type) {
        try { return MAPPER.readValue(json, type); }
        catch (Exception e) { throw new RuntimeException("json反序列化失败", e); }
    }
}
