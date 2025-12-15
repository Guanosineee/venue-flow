package com.venueflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_blog")
public class Blog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long venueId;
    private String title;
    private String content;
    private Integer liked;
    private LocalDateTime createTime;
}
