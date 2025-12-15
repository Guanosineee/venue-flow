package com.venueflow.controller;

import com.venueflow.dto.Result;
import com.venueflow.dto.UserDTO;
import com.venueflow.service.UserService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 发验证码（unsafe 模式会回显验证码，方便压测脚本拿码；正常模式返回 null） */
    @PostMapping("/code")
    public Result<String> sendCode(@RequestParam String phone) {
        return Result.ok(userService.sendCode(phone));
    }

    /** 登录（登录即注册），返回 token——之后所有请求放 authorization 头 */
    @PostMapping("/login")
    public Result<String> login(@RequestParam String phone, @RequestParam String code) {
        return Result.ok(userService.login(phone, code));
    }

    @GetMapping("/me")
    public Result<UserDTO> me() {
        return Result.ok(userService.me());
    }
}
