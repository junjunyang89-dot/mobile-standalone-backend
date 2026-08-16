-- 接口测试定时任务。启动时 ApiTestScheduleService 也会 CREATE IF NOT EXISTS，这份给手工建库用。
CREATE TABLE IF NOT EXISTS api_test_schedules (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    cron VARCHAR(64) NOT NULL COMMENT 'Spring 6 段 cron，如 0 0 8 * * ?',
    collection_id VARCHAR(64) NULL COMMENT '空则跑该项目下全部启用用例',
    project VARCHAR(64) NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    next_run_at DATETIME NULL,
    last_run_at DATETIME NULL,
    last_run_id VARCHAR(64) NULL,
    last_status VARCHAR(16) NULL,
    last_message VARCHAR(512) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_due (enabled, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
