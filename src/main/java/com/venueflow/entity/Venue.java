package com.venueflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 展馆：详情接口 = 缓存三兄弟实战位；lon/lat = GEO 模块数据源
@Data
@TableName("tb_venue")
public class Venue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String address;
    private Double lon;      // 经度
    private Double lat;      // 纬度
    private BigDecimal price; // 金额一律 BigDecimal——⭐小八股：double 有精度误差，0.1+0.2 != 0.3
    private String openHours;
    private LocalDateTime updateTime;
}
