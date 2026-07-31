-- =============================================================================
-- mobile-standalone-backend 完整数据库脚本（建表 + 增量迁移）
-- 使用说明：
--   - 新库：直接执行全文，会创建库、表及必要索引。
--   - 已有库：从下方「增量迁移」段开始执行；若某条报错（如 Duplicate column name），
--     表示该变更已存在，可忽略或注释该条后继续。
-- 执行前建议备份数据库。
-- =============================================================================

-- 创建库（若需指定库名，请修改下方名称；若已存在则跳过）
CREATE DATABASE IF NOT EXISTS mobile_standalone
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE mobile_standalone;

-- =============================================================================
-- 一、基础表（devices / apps / tasks / task_executions / settings）
-- =============================================================================

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    version VARCHAR(50),
    status VARCHAR(20) DEFAULT 'DISCONNECTED',
    last_seen DATETIME,
    config TEXT,
    INDEX idx_status (status),
    INDEX idx_platform (platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS apps (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    package_name VARCHAR(255) NOT NULL,
    version VARCHAR(50),
    device_id VARCHAR(64) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    icon_path VARCHAR(500),
    install_path VARCHAR(500) COMMENT '应用安装路径',
    install_time BIGINT COMMENT '安装时间（Unix时间戳，秒）',
    file_size BIGINT COMMENT '文件大小（字节）',
    config TEXT COMMENT '应用配置信息（JSON格式）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_device_id (device_id),
    INDEX idx_platform (platform),
    INDEX idx_package_name (package_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(64) PRIMARY KEY COMMENT '任务ID',
    name VARCHAR(255) NOT NULL COMMENT '任务名称',
    description TEXT COMMENT '备注信息',
    project_path VARCHAR(500),
    script_path VARCHAR(500),
    platform VARCHAR(20) NOT NULL COMMENT 'ANDROID, IOS, HARMONYOS',
    device_id VARCHAR(64),
    app_id VARCHAR(64),
    task_type VARCHAR(50),
    scenario_key VARCHAR(64) NULL DEFAULT NULL COMMENT '测试场景键：launch_app、login、search、launch_pogpog_android 等',
    status VARCHAR(20) DEFAULT 'PENDING',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    config TEXT COMMENT '额外配置（JSON，如登录测试 phoneNumber/verificationCode）',
    INDEX idx_status (status),
    INDEX idx_platform (platform),
    INDEX idx_device_id (device_id),
    INDEX idx_app_id (app_id),
    INDEX idx_task_type (task_type),
    INDEX idx_tasks_scenario_key (scenario_key),
    INDEX idx_created_at (created_at),
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL,
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试任务表';

CREATE TABLE IF NOT EXISTS task_executions (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    report_path VARCHAR(500),
    error_message TEXT,
    result_data TEXT COMMENT '结构化结果(JSON)，如兼容性测试的Appium会话数据',
    execution_log TEXT NULL COMMENT '执行步骤日志 JSON 数组',
    execution_variables TEXT NULL COMMENT '执行变量脱敏 JSON',
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_start_time (start_time),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS settings (
    `key` VARCHAR(100) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================================================
-- 二、自动化测试相关表（test_cases / test_plans / test_reports / page_objects）
-- =============================================================================

CREATE TABLE IF NOT EXISTS test_cases (
    id VARCHAR(64) PRIMARY KEY COMMENT '用例ID',
    name VARCHAR(255) NOT NULL,
    description TEXT,
    platform VARCHAR(20) NOT NULL,
    app_id VARCHAR(64),
    case_type VARCHAR(50),
    steps TEXT COMMENT '测试步骤（JSON）',
    expected_result TEXT,
    priority INT DEFAULT 5,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_platform (platform),
    INDEX idx_app_id (app_id),
    INDEX idx_case_type (case_type),
    INDEX idx_status (status),
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例表';

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

CREATE TABLE IF NOT EXISTS test_reports (
    id VARCHAR(64) PRIMARY KEY,
    plan_id VARCHAR(64),
    case_id VARCHAR(64),
    device_id VARCHAR(64),
    app_id VARCHAR(64),
    execution_id VARCHAR(64),
    status VARCHAR(20) NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    duration BIGINT,
    screenshot_paths TEXT,
    error_message TEXT,
    test_data TEXT,
    report_path VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_plan_id (plan_id),
    INDEX idx_case_id (case_id),
    INDEX idx_device_id (device_id),
    INDEX idx_app_id (app_id),
    INDEX idx_execution_id (execution_id),
    INDEX idx_status (status),
    INDEX idx_start_time (start_time),
    FOREIGN KEY (plan_id) REFERENCES test_plans(id) ON DELETE SET NULL,
    FOREIGN KEY (case_id) REFERENCES test_cases(id) ON DELETE SET NULL,
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL,
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试报告表';

CREATE TABLE IF NOT EXISTS page_objects (
    id VARCHAR(64) PRIMARY KEY COMMENT '页面对象ID',
    name VARCHAR(255) NOT NULL,
    description TEXT,
    platform VARCHAR(20) NOT NULL,
    app_id VARCHAR(64),
    page_path VARCHAR(500),
    catalog VARCHAR(32) NOT NULL DEFAULT 'AUTOMATION' COMMENT '元素目录：AUTOMATION / ONLINE / SHARED',
    elements TEXT COMMENT '页面元素定义（JSON）',
    created_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_platform (platform),
    INDEX idx_app_id (app_id),
    INDEX idx_catalog (catalog),
    INDEX idx_catalog_platform_app (catalog, platform, app_id),
    INDEX idx_platform_app_page (platform, app_id, page_path(100)),
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='页面对象模型表';

-- =============================================================================
-- 三、增量迁移（已有库执行；若报错 Duplicate column/key 表示已存在，可忽略）
-- =============================================================================

-- 3.1 test_plans：增加 scenario_id、scenario_config（若无则添加）
-- 若表已包含这两列，会报 Duplicate column name，可注释下面两段。
ALTER TABLE test_plans
    ADD COLUMN scenario_id VARCHAR(64) NULL COMMENT '场景ID(如login_android)' AFTER app_id;
ALTER TABLE test_plans
    ADD COLUMN scenario_config TEXT NULL COMMENT '场景执行时的config JSON' AFTER scenario_id;

-- 3.2 apps：补充 install_path、config（若建表时无此列则添加）
-- 使用“存在则跳过”的写法，避免重复执行报错。
SET @dbname = DATABASE();
SET @tablename = 'apps';

SET @col = 'install_path';
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @col) > 0,
    'SELECT 1',
    CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN ', @col, ' VARCHAR(500) COMMENT ''应用安装路径'';')
));
PREPARE p FROM @stmt;
EXECUTE p;
DEALLOCATE PREPARE p;

SET @col = 'config';
SET @stmt = (SELECT IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = @tablename AND COLUMN_NAME = @col) > 0,
    'SELECT 1',
    CONCAT('ALTER TABLE ', @tablename, ' ADD COLUMN ', @col, ' TEXT COMMENT ''应用配置信息（JSON格式）'';')
));
PREPARE p FROM @stmt;
EXECUTE p;
DEALLOCATE PREPARE p;

-- 3.3 page_objects：增加复合索引（若已存在会报 Duplicate key，可忽略）
ALTER TABLE page_objects
    ADD INDEX idx_platform_app_page (platform, app_id, page_path(100)) COMMENT '按平台+应用+页面查 POM 时加速';

-- 3.4 page_objects：元素目录 catalog（已有库执行；Duplicate column 可忽略）
ALTER TABLE page_objects
    ADD COLUMN catalog VARCHAR(32) NOT NULL DEFAULT 'AUTOMATION'
        COMMENT '元素目录：AUTOMATION / ONLINE / SHARED'
        AFTER page_path;
UPDATE page_objects SET catalog = 'AUTOMATION' WHERE catalog IS NULL OR catalog = '';
ALTER TABLE page_objects ADD INDEX idx_catalog (catalog);
ALTER TABLE page_objects ADD INDEX idx_catalog_platform_app (catalog, platform, app_id);

-- =============================================================================
-- 结束
-- =============================================================================
