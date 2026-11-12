-- =============================================================================
-- change-monitor P0 数据库 DDL
-- MySQL 8.0 / InnoDB / utf8mb4 ; 时间 DATETIME(3) UTC 存储
-- 约束：只存内容指纹与差异摘要，不存网页全文（page_snapshot 无 raw_content）
-- id 为应用侧雪花 ID（MyBatis-Plus ASSIGN_ID），不使用 AUTO_INCREMENT
-- 说明：本文件不自动执行（spring.sql.init.mode=never），首次建库请手动导入
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `change_monitor`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `change_monitor`;

-- 1. user_account ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_account` (
    `id`                 BIGINT UNSIGNED NOT NULL COMMENT '雪花ID',
    `username`           VARCHAR(64)     NOT NULL COMMENT '用户名',
    `password_hash`      VARCHAR(100)    NOT NULL COMMENT 'BCrypt 密码哈希',
    `email`              VARCHAR(128)    NOT NULL COMMENT '邮箱',
    `phone`              VARCHAR(32)     DEFAULT NULL COMMENT '手机号',
    `role`               VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN',
    `status`             VARCHAR(16)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/DISABLED',
    `quota_subscription` INT             NOT NULL DEFAULT 20 COMMENT '订阅数配额',
    `quota_scan_daily`   INT             NOT NULL DEFAULT 288 COMMENT '日扫描配额',
    `last_login_at`      DATETIME(3)     DEFAULT NULL,
    `created_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`            TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户账户';

-- 2. subscription ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `subscription` (
    `id`                      BIGINT UNSIGNED NOT NULL,
    `user_id`                 BIGINT UNSIGNED NOT NULL,
    `name`                    VARCHAR(128)    NOT NULL COMMENT '订阅名称',
    `url`                     VARCHAR(1024)   NOT NULL COMMENT '公开网页URL',
    `url_hash`                CHAR(64)        NOT NULL COMMENT 'SHA-256(normalized_url)',
    `domain`                  VARCHAR(255)    NOT NULL COMMENT '域名，限速与黑名单依据',
    `selector_type`           VARCHAR(16)     NOT NULL DEFAULT 'CSS' COMMENT 'CSS/KEYWORD/REGEX',
    `selector_expr`           VARCHAR(512)    DEFAULT NULL COMMENT '选择器/正则',
    `keyword`                 VARCHAR(255)    DEFAULT NULL COMMENT '关键词',
    `check_interval_sec`      INT             NOT NULL DEFAULT 1800 COMMENT '检查间隔秒，最小300',
    `status`                  VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/PAUSED/ABNORMAL/TERMINATED',
    `consecutive_diff_count`  TINYINT         NOT NULL DEFAULT 0 COMMENT '连续指纹不同次数，>=2 生成事件',
    `last_fingerprint`        CHAR(64)        DEFAULT NULL COMMENT '上次成功抓取的内容指纹',
    `last_scan_at`            DATETIME(3)     DEFAULT NULL,
    `last_change_at`          DATETIME(3)     DEFAULT NULL,
    `next_scan_at`            DATETIME(3)     NOT NULL COMMENT '调度扫描唯一依据',
    `fail_count`              SMALLINT        NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    `enabled`                 TINYINT         NOT NULL DEFAULT 1,
    `created_at`              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`                 TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除(TERMINATED)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_url` (`user_id`, `url_hash`),
    KEY `idx_status_next` (`status`, `next_scan_at`),
    KEY `idx_domain` (`domain`),
    KEY `idx_user` (`user_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '订阅主体/状态机';

-- 3. scan_task ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `scan_task` (
    `id`               BIGINT UNSIGNED NOT NULL,
    `subscription_id`  BIGINT UNSIGNED NOT NULL,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `trigger_type`     VARCHAR(16)     NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED/MANUAL/RETRY',
    `batch_no`         VARCHAR(32)     DEFAULT NULL COMMENT '调度批次',
    `status`           VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT',
    `idempotency_key`  VARCHAR(64)     NOT NULL COMMENT '幂等键,Redis 24h + DB 唯一',
    `http_status`      INT             DEFAULT NULL,
    `cost_ms`          INT             DEFAULT NULL,
    `error_code`       VARCHAR(32)     DEFAULT NULL,
    `error_msg`        VARCHAR(512)    DEFAULT NULL,
    `start_at`         DATETIME(3)     DEFAULT NULL,
    `end_at`           DATETIME(3)     DEFAULT NULL,
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
    KEY `idx_sub_time` (`subscription_id`, `created_at`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_batch` (`batch_no`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '扫描执行记录';

-- 4. page_snapshot -----------------------------------------------------------
-- 仅指纹与差异摘要，禁止新增 raw_content 类字段
CREATE TABLE IF NOT EXISTS `page_snapshot` (
    `id`                  BIGINT UNSIGNED NOT NULL,
    `subscription_id`     BIGINT UNSIGNED NOT NULL,
    `scan_task_id`        BIGINT UNSIGNED NOT NULL,
    `content_fingerprint` CHAR(64)        NOT NULL COMMENT 'SHA-256 指纹',
    `content_length`      INT             NOT NULL DEFAULT 0 COMMENT '提取文本长度(辅助判定)',
    `diff_summary`        VARCHAR(500)    DEFAULT NULL COMMENT '差异摘要,不含原文',
    `is_changed`          TINYINT         NOT NULL DEFAULT 0,
    `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`             TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sub_fingerprint` (`subscription_id`, `content_fingerprint`),
    KEY `idx_sub_time` (`subscription_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '页面指纹快照(无全文)';

-- 5. change_event ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `change_event` (
    `id`               BIGINT UNSIGNED NOT NULL,
    `event_no`         VARCHAR(32)     NOT NULL COMMENT '对外业务编号,SSE 使用',
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `subscription_id`  BIGINT UNSIGNED NOT NULL,
    `snapshot_id`      BIGINT UNSIGNED NOT NULL,
    `change_type`      VARCHAR(24)     NOT NULL DEFAULT 'CONTENT' COMMENT 'CONTENT/KEYWORD_HIT/URL_UNAVAILABLE',
    `diff_summary`     VARCHAR(500)    DEFAULT NULL,
    `confirmed`        TINYINT         NOT NULL DEFAULT 0 COMMENT '二次确认标志;未确认不进时间线/不通知',
    `read_flag`        TINYINT         NOT NULL DEFAULT 0,
    `notify_status`    VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/PARTIAL/FAILED',
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_no` (`event_no`),
    UNIQUE KEY `uk_sub_snapshot` (`subscription_id`, `snapshot_id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_notify_status` (`notify_status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户可见变更事实';

-- 6. notification_record ----------------------------------------------------
CREATE TABLE IF NOT EXISTS `notification_record` (
    `id`               BIGINT UNSIGNED NOT NULL,
    `event_id`         BIGINT UNSIGNED NOT NULL,
    `user_id`          BIGINT UNSIGNED NOT NULL,
    `channel`          VARCHAR(16)     NOT NULL COMMENT 'SITE/EMAIL/WECOM',
    `target`           VARCHAR(255)    DEFAULT NULL COMMENT '收件地址/站内用户',
    `subject`          VARCHAR(255)    DEFAULT NULL,
    `content_digest`   VARCHAR(500)    DEFAULT NULL COMMENT '摘要,不含原文',
    `status`           VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/RETRY/SKIPPED',
    `retry_count`      SMALLINT        NOT NULL DEFAULT 0,
    `idempotency_key`  VARCHAR(64)     NOT NULL,
    `sent_at`          DATETIME(3)     DEFAULT NULL,
    `error_msg`        VARCHAR(512)    DEFAULT NULL,
    `created_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`          TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
    KEY `idx_event` (`event_id`),
    KEY `idx_status_created` (`status`, `created_at`),
    KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '通知发送记录';

-- 7. notify_channel_config ---------------------------------------------------
CREATE TABLE IF NOT EXISTS `notify_channel_config` (
    `id`                  BIGINT UNSIGNED NOT NULL,
    `user_id`             BIGINT UNSIGNED NOT NULL,
    `channel`             VARCHAR(16)     NOT NULL COMMENT 'SITE/EMAIL/WECOM',
    `target`              VARCHAR(255)    NOT NULL DEFAULT '' COMMENT '收件地址等',
    `enabled`             TINYINT         NOT NULL DEFAULT 1,
    `freq_limit_per_day`  INT             NOT NULL DEFAULT 20 COMMENT '用户级每日频率上限',
    `last_sent_at`        DATETIME(3)     DEFAULT NULL,
    `created_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`             TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_channel_target` (`user_id`, `channel`, `target`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '通知渠道与频率配置';

-- 8. source_blacklist --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `source_blacklist` (
    `id`             BIGINT UNSIGNED NOT NULL,
    `domain_pattern` VARCHAR(255)    NOT NULL COMMENT '域名或通配模式',
    `reason`         VARCHAR(512)    DEFAULT NULL,
    `status`         VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DISABLED',
    `created_by`     BIGINT UNSIGNED DEFAULT NULL,
    `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`        TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_pattern` (`domain_pattern`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '源站黑名单';

-- 9. audit_log ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`             BIGINT UNSIGNED NOT NULL,
    `user_id`        BIGINT UNSIGNED DEFAULT NULL,
    `action`         VARCHAR(64)     NOT NULL,
    `resource_type`  VARCHAR(64)     DEFAULT NULL,
    `resource_id`    VARCHAR(64)     DEFAULT NULL,
    `detail`         VARCHAR(1024)   DEFAULT NULL,
    `ip`             VARCHAR(64)     DEFAULT NULL,
    `trace_id`       VARCHAR(64)     DEFAULT NULL,
    `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`        TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_trace` (`trace_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '审计日志';

-- 10. quota_usage ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `quota_usage` (
    `id`           BIGINT UNSIGNED NOT NULL,
    `user_id`      BIGINT UNSIGNED NOT NULL,
    `stat_date`    DATE            NOT NULL,
    `scan_count`   INT             NOT NULL DEFAULT 0,
    `notify_count` INT             NOT NULL DEFAULT 0,
    `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`      TINYINT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `stat_date`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '配额日落库对账';

-- 11. sys_config -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_config` (
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '主键',
    `config_value` VARCHAR(512) NOT NULL,
    `remark`       VARCHAR(255) DEFAULT NULL,
    `updated_by`   BIGINT UNSIGNED DEFAULT NULL,
    `created_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`config_key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '全局配置热更新';

-- 默认全局配置
INSERT IGNORE INTO `sys_config` (`config_key`, `config_value`, `remark`) VALUES
    ('scan.min_interval_sec', '300', '最小检查间隔(秒)'),
    ('scan.domain_min_gap_sec', '30', '同域名扫描最小间隔(秒)'),
    ('scan.max_retry', '2', '抓取失败最大重试次数'),
    ('scan.max_fail_count', '5', '连续失败置 ABNORMAL 阈值'),
    ('scan.diff_confirm_threshold', '2', '连续指纹不同确认次数'),
    ('notify.default_freq_limit_per_day', '20', '默认用户级每日通知上限');

-- 默认管理员由应用启动类 AdminInitializer 用 BCrypt 真实编码创建(用户名 admin / 密码 admin123)，
-- 避免此处硬编码不可靠的哈希；上线前请修改默认密码。
