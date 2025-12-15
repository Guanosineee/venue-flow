package com.venueflow.config;

import com.venueflow.dto.Result;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常出口：业务代码放心 throw，不用层层 try-catch 包粽子。
 * 返回统一 Result 结构——前端拿到的永远是约定过的 JSON，而不是 Spring 默认的 500 白页。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handle(RuntimeException e) {
        return Result.fail(e.getMessage() == null ? "服务异常" : e.getMessage());
    }
}
