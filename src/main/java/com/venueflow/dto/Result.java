package com.venueflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 统一响应体（所有接口返回 {code, msg, data}）
// ⭐面试点：统一返回结构 = 前后端契约；code 用 Integer 避免 null 拆箱 NPE
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> {
    private Integer code;   // 0=成功，非0=失败
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) { return new Result<>(0, "ok", data); }
    public static <T> Result<T> ok() { return ok(null); }
    public static <T> Result<T> fail(String msg) { return new Result<>(1, msg, null); }
}
