-- APP 线上回归：账号池 + 批次/作业/步骤（与功能测试 tasks 解耦，复用 scenario 执行）
-- mysql ... < schema_online_regression.sql

CREATE TABLE IF NOT EXISTS online_regression_accounts (
    id VARCHAR(64) PRIMARY KEY,
    app_id VARCHAR(32) NOT NULL COMMENT 'DOMESTIC_ESPORTS / OVERSEAS_POGPOG',
    label VARCHAR(128) NULL COMMENT '展示名',
    phone_number VARCHAR(32) NOT NULL COMMENT '登录手机号',
    verification_code VARCHAR(32) NOT NULL COMMENT '验证码',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_or_acc_app (app_id),
    INDEX idx_or_acc_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线上回归测试账号池';

CREATE TABLE IF NOT EXISTS online_regression_runs (
    id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/STOPPED',
    selected_apps TEXT NOT NULL COMMENT 'JSON 数组',
    selected_features TEXT NOT NULL COMMENT 'JSON 数组',
    ordered_steps TEXT NULL COMMENT '排序后原子步骤 JSON',
    enable_overseas_proxy TINYINT(1) NOT NULL DEFAULT 0,
    environment VARCHAR(16) NULL COMMENT 'PROD/TEST，空按 PROD',
    track VARCHAR(16) NULL COMMENT 'REGRESSION/AUTOMATION，空按 REGRESSION',
    pass_rate INT NULL,
    error_message TEXT NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_or_run_status (status),
    INDEX idx_or_run_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线上回归批次';

CREATE TABLE IF NOT EXISTS online_regression_jobs (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    job_key VARCHAR(191) NOT NULL COMMENT 'appId__deviceId',
    app_id VARCHAR(32) NOT NULL,
    app_name VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(255) NULL,
    device_platform VARCHAR(32) NOT NULL,
    endpoint VARCHAR(16) NOT NULL COMMENT 'android/ios/other',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    current_step_label VARCHAR(128) NULL,
    account_phone VARCHAR(32) NULL,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_or_job_run (run_id),
    CONSTRAINT fk_or_job_run FOREIGN KEY (run_id) REFERENCES online_regression_runs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线上回归 APP×设备作业';

CREATE TABLE IF NOT EXISTS online_regression_steps (
    id VARCHAR(64) PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL COMMENT 'launch/login/...',
    step_label VARCHAR(128) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/SKIPPED',
    task_id VARCHAR(64) NULL COMMENT '关联 tasks.id',
    screenshot_path TEXT NULL,
    error_message TEXT NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_or_step_job (job_id),
    INDEX idx_or_step_task (task_id),
    CONSTRAINT fk_or_step_job FOREIGN KEY (job_id) REFERENCES online_regression_jobs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线上回归原子步骤';

-- 默认线上回归账号（与专项测试默认号分离）
INSERT INTO online_regression_accounts (id, app_id, label, phone_number, verification_code, enabled, sort_order, created_at, updated_at)
SELECT 'or-acc-domestic-01', 'DOMESTIC_ESPORTS', '线上回归账号1', '18851663603', '487721', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM online_regression_accounts WHERE id = 'or-acc-domestic-01');

INSERT INTO online_regression_accounts (id, app_id, label, phone_number, verification_code, enabled, sort_order, created_at, updated_at)
SELECT 'or-acc-domestic-02', 'DOMESTIC_ESPORTS', '线上回归账号2', '12312340001', '660032', 1, 2, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM online_regression_accounts WHERE id = 'or-acc-domestic-02');

INSERT INTO online_regression_accounts (id, app_id, label, phone_number, verification_code, enabled, sort_order, created_at, updated_at)
SELECT 'or-acc-pog-01', 'OVERSEAS_POGPOG', 'PogPog线上账号1', '18851663603', '487721', 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM online_regression_accounts WHERE id = 'or-acc-pog-01');

INSERT INTO online_regression_accounts (id, app_id, label, phone_number, verification_code, enabled, sort_order, created_at, updated_at)
SELECT 'or-acc-pog-02', 'OVERSEAS_POGPOG', 'PogPog线上账号2', '12312340001', '660032', 1, 2, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM online_regression_accounts WHERE id = 'or-acc-pog-02');
