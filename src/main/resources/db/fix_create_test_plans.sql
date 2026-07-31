-- 修复错误：Table 'mobile_standalone.test_plans' doesn't exist
-- 在 mobile_standalone 库下执行本脚本即可（需已存在 devices、apps 表）

USE mobile_standalone;

CREATE TABLE IF NOT EXISTS test_plans (
    id VARCHAR(64) PRIMARY KEY COMMENT '计划ID',
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cron_expression VARCHAR(100) NOT NULL,
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai',
    device_id VARCHAR(64),
    app_id VARCHAR(64),
    scenario_id VARCHAR(64) NULL COMMENT '场景ID(如login_android)，与case_ids二选一',
    scenario_config TEXT NULL COMMENT '场景执行时的config JSON',
    case_ids TEXT COMMENT '用例ID列表（JSON数组）',
    enabled BOOLEAN DEFAULT TRUE,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    last_run_time DATETIME,
    next_run_time DATETIME,
    run_count INT DEFAULT 0,
    created_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_device_id (device_id),
    INDEX idx_app_id (app_id),
    INDEX idx_enabled (enabled),
    INDEX idx_status (status),
    INDEX idx_next_run_time (next_run_time),
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL,
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试计划表';
