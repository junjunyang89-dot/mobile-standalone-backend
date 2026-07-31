-- 注意：数据库需要在连接 URL 中指定，或手动创建
-- CREATE DATABASE IF NOT EXISTS mobile_standalone DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 设备表
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

-- 任务表
-- 存储测试任务信息，包括任务名称、备注、设备、应用等
CREATE TABLE IF NOT EXISTS tasks (
    id VARCHAR(64) PRIMARY KEY COMMENT '任务ID',
    name VARCHAR(255) NOT NULL COMMENT '任务名称（必填，1-60字符）',
    description TEXT COMMENT '备注信息（可选，最多80字符）',
    project_path VARCHAR(500) COMMENT '项目路径',
    script_path VARCHAR(500) COMMENT '测试脚本路径',
    platform VARCHAR(20) NOT NULL COMMENT '平台类型：ANDROID, IOS, HARMONYOS',
    device_id VARCHAR(64) COMMENT '测试设备ID（必填，关联devices表）',
    app_id VARCHAR(64) COMMENT '已安装的应用ID（必填，关联apps表）',
    task_type VARCHAR(50) COMMENT '任务类型：FUNCTIONAL_QUALITY_TEST(功能体验基础质量测试), STABILITY_QUALITY_TEST(稳定性基础质量测试), PERFORMANCE_QUALITY_TEST(性能基础质量测试), POWER_CONSUMPTION_QUALITY_TEST(功耗基础质量测试), PERFORMANCE_MONITORING_TEST(性能指标监控测试), REGRESSION_TEST(回归测试)',
    scenario_key VARCHAR(64) NULL DEFAULT NULL COMMENT '测试场景键（功能基础质量）：launch_app、login、search、launch_pogpog_android 等',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT '任务状态：PENDING, RUNNING, COMPLETED, FAILED, STOPPED',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    config TEXT COMMENT '额外配置信息（JSON格式）',
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

-- 任务执行历史表
CREATE TABLE IF NOT EXISTS task_executions (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    report_path VARCHAR(500),
    error_message TEXT,
    result_data TEXT COMMENT '结构化结果数据(JSON)，如兼容性测试的设备/应用/会话信息',
    execution_log TEXT NULL COMMENT '执行步骤日志 JSON 数组',
    execution_variables TEXT NULL COMMENT '执行变量脱敏 JSON，如 phoneNumberMasked、verificationCodeMasked',
    INDEX idx_task_id (task_id),
    INDEX idx_status (status),
    INDEX idx_start_time (start_time),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 应用表
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

-- 配置表
CREATE TABLE IF NOT EXISTS settings (
    `key` VARCHAR(100) PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
