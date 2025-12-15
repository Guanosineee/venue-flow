package com.venueflow.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// 存进 Redis 会话的用户信息。
// ⭐面试点：会话里只存必要字段（最小化原则）——不把整个 User 实体塞进 Redis，
// 一是省内存，二是实体演进（加字段）不会让旧会话反序列化炸掉
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDTO {
    private Long id;
    private String nickName;
}
