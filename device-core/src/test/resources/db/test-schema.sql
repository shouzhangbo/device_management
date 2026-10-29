-- =============================================================================
-- 集成测试建表夹具（Testcontainers MySQL 8）——仅测试用，不是生产迁移产物。
--
-- 生产 DDL 的唯一事实来源是 data-model.md §6，由运维在发布前人工执行
-- （需求方 2026-10-29 裁决"DDL 不需要迁移"，本期不引 flyway-core、不落 db/migration/*.sql）。
-- 本文件与 data-model.md §6.1~§6.5 出现任何差异都视为缺陷。
--
-- 版本下限：MySQL 8.0.13。低于该版本时 `ext_json` / `before_json` / `after_json` 的
-- `NOT NULL DEFAULT (JSON_OBJECT())` 表达式默认值无法建表（data-model §6.1 注记 1）。
--
-- 与 §6 的唯一差异：§6.5 授权表初始数据里的 source 是"待业务确认"的占位编码，
-- 这里换成测试用固定串，见文件末尾。PROFILE / MAINTAIN 仍为 'ADMIN'，
-- 因为后台写路径的 source 由 DeviceAdminService.ADMIN_SOURCE 硬编码，不可替换。
-- =============================================================================

DROP TABLE IF EXISTS `device_info`;
DROP TABLE IF EXISTS `device_info_change_record`;
DROP TABLE IF EXISTS `mq_consume_record`;
DROP TABLE IF EXISTS `mq_dead_letter`;
DROP TABLE IF EXISTS `device_dim_source_auth`;

-- data-model.md §6.1 设备主档
CREATE TABLE `device_info` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `sn`              VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '设备序列号，业务唯一键',
  `model_code`      VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '设备型号编码',
  `model_name`      VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '设备型号名称（冗余存储，避免后台列表 join 字典）',
  `factory_code`    VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '生产工厂编码',
  `factory_name`    VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '生产工厂名称（冗余存储）',
  `produce_time`    DATETIME      NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '生产时间；哨兵值表示外部尚未同步',
  `register_status` TINYINT       NOT NULL DEFAULT 0   COMMENT '出厂注册状态：0-未注册 1-已注册（外部 FACTORY_REGISTER 写入）',
  `online_status`   TINYINT       NOT NULL DEFAULT 0   COMMENT '在线状态：0-未知 1-在线 2-离线（外部 ONLINE_CHANGE 写入）',
  `bind_status`     TINYINT       NOT NULL DEFAULT 0   COMMENT '绑定状态：0-未绑定 1-已绑定（外部 BIND_CHANGE 写入）',
  `bind_target`     VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '绑定目标标识（门店/机柜等业务编码，由外部定义）',
  `bind_time`       DATETIME      NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '绑定时间；哨兵值表示未同步',
  `recover_status`  TINYINT       NOT NULL DEFAULT 0   COMMENT '回收报废状态：0-正常 1-已回收 2-已报废（外部 RECOVER_CHANGE 写入，本系统不校验流转方向）',
  `scrap_time`      DATETIME      NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '报废时间；哨兵值表示未同步',
  `remark`          VARCHAR(512)  NOT NULL DEFAULT ''  COMMENT '备注（后台 PROFILE 维度维护）',
  `image_url`       VARCHAR(512)  NOT NULL DEFAULT ''  COMMENT '设备图片 URL，公网可读，无需签名',
  `maintain_user`   VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '最近维护人（后台登录账号拼音形式）；不进缓存、不对外',
  `maintain_time`   DATETIME      NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '最近维护时间；不进缓存、不对外',
  `ext_json`        JSON          NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '外部透传的附加信息，不参与筛选、不进缓存、不对外',
  `version`         INT UNSIGNED  NOT NULL DEFAULT 0   COMMENT '乐观锁版本号，每次成功更新 +1',
  `deleted`         TINYINT       NOT NULL DEFAULT 0   COMMENT '逻辑删除：0-正常 1-已删除（查询侧视为不存在；外部重推可更新维度列，见 tech-design R6）',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，同时作为缓存对账水位',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sn` (`sn`) COMMENT '设备序列号业务唯一（幂等建档与缓存回源的硬约束）',
  KEY `idx_model_code_produce_time` (`model_code`, `produce_time`) COMMENT '后台按型号+生产时间筛选；高区分度列在最左（MySQL §1.2）',
  KEY `idx_bind_target` (`bind_target`(32)) COMMENT '按绑定目标反查设备；VARCHAR 索引指定前缀长度（MySQL §1.2）',
  KEY `idx_update_time` (`update_time`) COMMENT 'EP-007 缓存对账按更新时间增量扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备主档表（外部系统的本地镜像）';

-- data-model.md §6.2 设备变更流水（按月分区，主键必须含分区键）
CREATE TABLE `device_info_change_record` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `sn`          VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '设备序列号',
  `source`      VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '变更来源：外部 source 编码或 ADMIN',
  `msg_id`      VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '消息 ID；后台操作填本地生成的操作号',
  `msg_type`    VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT '消息类型，见 §8.4',
  `dim_type`    VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT '本次变更影响的维度，见 §8.4',
  `before_json` JSON          NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '变更前该维度的列值快照',
  `after_json`  JSON          NOT NULL DEFAULT (JSON_OBJECT()) COMMENT '变更后该维度的列值快照',
  `result`      TINYINT       NOT NULL DEFAULT 1   COMMENT '处理结果：1-已更新 3-设备不存在 4-未授权来源（重复与版本冲突不入此表，见 tech-design 附录 B）',
  `op_user`     VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '操作人（source=ADMIN 时填账号拼音；消息来源为空）',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，同时为分区键',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（流水不改写，仅为规范必备字段）',
  PRIMARY KEY (`id`, `create_time`),
  KEY `idx_sn_create_time` (`sn`, `create_time`) COMMENT '设备详情时间轴与人工纠正排查',
  KEY `idx_source_msg` (`source`, `msg_id`) COMMENT '按消息定位本次处理结果（非唯一：允许重试留多条）',
  KEY `idx_create_time` (`create_time`) COMMENT '按时间范围审计与归档核对'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备维度变更流水表（只增不改，按月分区）'
PARTITION BY RANGE (TO_DAYS(`create_time`)) (
  PARTITION `p202610` VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION `p202611` VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION `p202612` VALUES LESS THAN (TO_DAYS('2027-01-01')),
  PARTITION `pmax`    VALUES LESS THAN MAXVALUE
);

-- data-model.md §6.3 消费幂等表（不分区，见 §8.3）
CREATE TABLE `mq_consume_record` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `msg_id`       VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '消息 ID（与 source 组合构成幂等键）',
  `source`       VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '消息来源系统编码',
  `biz_type`     VARCHAR(64)   NOT NULL DEFAULT 'device' COMMENT '业务类型',
  `biz_id`       VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '业务 ID，即设备 sn',
  `msg_type`     VARCHAR(32)   NOT NULL DEFAULT ''  COMMENT '消息类型',
  `topic`        VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '来源 Topic',
  `status`       TINYINT       NOT NULL DEFAULT 1   COMMENT '消费结果：1-成功 2-失败待重试 3-已进入死信',
  `fail_reason`  VARCHAR(1000) NOT NULL DEFAULT ''  COMMENT '失败原因摘要',
  `consume_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
  `update_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（状态回写用）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_msg` (`source`, `msg_id`) COMMENT '消费幂等的硬保证；比规范模板 uk_msg_id 多一列 source，理由见 §8.2',
  KEY `idx_biz` (`biz_type`, `biz_id`) COMMENT '按设备反查消费记录，用于对账与排障',
  KEY `idx_status_consume_time` (`status`, `consume_time`) COMMENT '失败/死信扫描与告警（MQ §4.4【强制】状态列在左）',
  KEY `idx_consume_time` (`consume_time`) COMMENT 'EP-008 按保留期分批清理'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ 消费幂等记录表（不分区，原因见 §8.3）';

-- data-model.md §6.4 死信归档表
CREATE TABLE `mq_dead_letter` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `msg_id`         VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '消息 ID',
  `source`         VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '消息来源（可能为空：信封不可解析时）',
  `topic`          VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '原始 Topic',
  `consumer_group` VARCHAR(128)  NOT NULL DEFAULT ''  COMMENT '消费组（多消费组排障时区分归属）',
  `partition_no`   INT           NOT NULL DEFAULT 0   COMMENT '原始分区号',
  `offset_no`      BIGINT        NOT NULL DEFAULT 0   COMMENT '原始 Offset（重放定位用）',
  `msg_key`        VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '原始消息 key，即 sn（不可解析时为空）',
  `content`        TEXT          NOT NULL COMMENT '原始消息体（JSON 文本）',
  `fail_reason`    VARCHAR(1000) NOT NULL DEFAULT ''  COMMENT '失败原因',
  `retry_count`    INT           NOT NULL DEFAULT 0   COMMENT '已重试次数',
  `status`         TINYINT       NOT NULL DEFAULT 0   COMMENT '处理状态：0-待处理 1-已重放 2-已丢弃',
  `handle_user`    VARCHAR(64)   NOT NULL DEFAULT ''  COMMENT '处理人（EP-011 重放/丢弃时写入）',
  `handle_time`    DATETIME      NOT NULL DEFAULT '1970-01-01 00:00:00' COMMENT '处理时间',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '进入死信时间',
  `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_create_time` (`status`, `create_time`) COMMENT '待处理死信扫描与告警（MQ §4.4【强制】）',
  KEY `idx_msg_id` (`msg_id`) COMMENT '按消息定位/重放（MQ §4.4【推荐】）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ 死信表：可查询、可重放、有管理入口（EP-011）';

-- data-model.md §6.5 维度权威源授权表
CREATE TABLE `device_dim_source_auth` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `dim_type`    VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '维度：FACTORY/ONLINE/BIND/RECOVER/PROFILE/MAINTAIN',
  `source`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '被授权写该维度的来源：外部 source 编码或 ADMIN',
  `enabled`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：0-关闭 1-启用',
  `remark`      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '授权说明（人工维护，变更需留档）',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dim_source` (`dim_type`, `source`) COMMENT '同一维度同一来源只允许一条授权记录'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='维度权威源授权表（EP-012 加载，无 event_time 条件下的核心覆盖防线）';

-- 授权初始数据（测试替身）：外部四维用固定测试串，后台两维必须是 'ADMIN'。
-- 'UNKNOWN_SOURCE' 故意不入库，供"未授权来源被静默拒"的用例使用。
INSERT INTO `device_dim_source_auth` (`dim_type`, `source`, `enabled`, `remark`) VALUES
  ('FACTORY',  'TEST_SRC_FACTORY', 1, '测试用出厂注册权威源'),
  ('ONLINE',   'TEST_SRC_ONLINE',  1, '测试用在线状态权威源'),
  ('BIND',     'TEST_SRC_BIND',    1, '测试用绑定关系权威源'),
  ('RECOVER',  'TEST_SRC_RECOVER', 1, '测试用回收/报废权威源'),
  ('PROFILE',  'ADMIN',            1, '型号/备注/图片由运营维护'),
  ('MAINTAIN', 'ADMIN',            1, '维护人与维护时间仅后台可写');
