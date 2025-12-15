-- 展遇 expomeet 建库建表 + 种子数据
-- 被 docker-compose 的 /docker-entrypoint-initdb.d 自动执行
CREATE DATABASE IF NOT EXISTS venue_flow DEFAULT CHARACTER SET utf8mb4;
USE venue_flow;

-- 用户表（扫码/手机验证码登录，注册即创建）
CREATE TABLE IF NOT EXISTS tb_user (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone       VARCHAR(11) NOT NULL,
    nick_name   VARCHAR(32) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_phone (phone)
) ENGINE=InnoDB;

-- 展馆表（详情页 = 缓存三兄弟的主战场；lon/lat 供 GEO 模块）
CREATE TABLE IF NOT EXISTS tb_venue (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL COMMENT '展馆名',
    address     VARCHAR(128) NOT NULL,
    lon         DOUBLE NOT NULL COMMENT '经度',
    lat         DOUBLE NOT NULL COMMENT '纬度',
    price       DECIMAL(10,2) NOT NULL COMMENT '常规门票参考价',
    open_hours  VARCHAR(64) DEFAULT '09:00-17:00 周一闭馆',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 门票表（stock/begin/end 支撑秒杀；秒杀场单独一条记录）
CREATE TABLE IF NOT EXISTS tb_ticket (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    venue_id    BIGINT NOT NULL,
    title       VARCHAR(128) NOT NULL,
    pay_amount  DECIMAL(10,2) NOT NULL,
    stock       INT NOT NULL DEFAULT 0 COMMENT '剩余库存（秒杀时同步 Redis 后以 DB 为准兜底）',
    begin_time  DATETIME NOT NULL COMMENT '秒杀开始',
    end_time    DATETIME NOT NULL COMMENT '秒杀结束',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_venue (venue_id)
) ENGINE=InnoDB;

-- 订单表
-- ⭐面试点：uk_user_ticket = 一人一单的 DB 兜底。
-- 即使并发检查全被绕过，重复插入也会撞唯一键——这是"幂等"的最后一道墙
CREATE TABLE IF NOT EXISTS tb_ticket_order (
    id          BIGINT PRIMARY KEY COMMENT 'RedisIdWorker 生成的分布式ID',
    user_id     BIGINT NOT NULL,
    ticket_id   BIGINT NOT NULL,
    pay_amount  DECIMAL(10,2) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_ticket (user_id, ticket_id),
    KEY idx_ticket (ticket_id)
) ENGINE=InnoDB;

-- 展评表（点赞/热榜模块的载体）
CREATE TABLE IF NOT EXISTS tb_blog (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL,
    venue_id    BIGINT NOT NULL,
    title       VARCHAR(128) NOT NULL,
    content     VARCHAR(1024) NOT NULL,
    liked       INT NOT NULL DEFAULT 0 COMMENT '点赞数（ZSET 为准，此列做展示冗余）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- 关注表（共同关注 = SET 交集；Redis 为主，此表兜底/回溯）
CREATE TABLE IF NOT EXISTS tb_follow (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT NOT NULL COMMENT '关注发起人',
    follow_user_id BIGINT NOT NULL COMMENT '被关注人（金牌讲解员）',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follow (user_id, follow_user_id)
) ENGINE=InnoDB;

-- ---------- 种子数据 ----------

INSERT INTO tb_venue (name, address, lon, lat, price) VALUES
('国家博物馆', '北京市东城区东长安街16号', 116.404, 39.905, 0.00),
('故宫博物院', '北京市东城区景山前街4号', 116.397, 39.918, 60.00),
('首都博物馆', '北京市西城区复兴门外大街16号', 116.336, 39.903, 0.00),
('中国科学技术馆', '北京市朝阳区北辰东路5号', 116.398, 40.005, 30.00),
('798艺术区', '北京市朝阳区酒仙桥路2号', 116.495, 39.984, 50.00),
('中国美术馆', '北京市东城区五四大街1号', 116.409, 39.925, 0.00),
('军事博物馆', '北京市海淀区复兴路9号', 116.323, 39.907, 0.00),
('自然博物馆', '北京市东城区天桥南大街126号', 116.393, 39.875, 20.00),
('北京天文馆', '北京市西城区西直门外大街138号', 116.345, 39.938, 25.00),
('中国铁道博物馆', '北京市朝阳区酒仙桥北一街', 116.432, 39.902, 15.00),
('北京汽车博物馆', '北京市丰台区规划路4号', 116.308, 39.858, 30.00),
('民族文化宫博物馆', '北京市西城区复兴门内大街49号', 116.362, 39.912, 0.00);

-- 秒杀门票：id=1 特展票 100 张（开始时间已过、结束 7 天后，拿来即测）
INSERT INTO tb_ticket (id, venue_id, title, pay_amount, stock, begin_time, end_time) VALUES
(1, 1, '特展《青铜的荣耀》门票·秒杀场', 9.90, 100, NOW() - INTERVAL 2 HOUR, NOW() + INTERVAL 7 DAY),
(2, 5, '798数字艺术展门票·秒杀场', 59.00, 500, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 30 DAY);

INSERT INTO tb_blog (user_id, venue_id, title, content, liked) VALUES
(1, 1, '青铜展太震撼了', '司母戊鼎旁边站了十分钟，导览机器人讲到商周分界的时候特意放慢语速，细节拉满。', 12),
(1, 2, '故宫雪景攻略', '早八入园直奔太和殿，人少光线好，雪后的红墙值得专门请一天假。', 8),
(1, 4, '科技馆带娃指南', '主展厅四层的机械之舞整点开场，提前十分钟去占位。', 5);
