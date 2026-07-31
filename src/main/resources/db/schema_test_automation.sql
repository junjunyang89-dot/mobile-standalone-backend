-- 自动化测试调度系统数据库表结构
-- 执行时间：2024-01-15

-- 测试用例表
CREATE TABLE IF NOT EXISTS test_cases (
    id VARCHAR(64) PRIMARY KEY COMMENT '用例ID',
    name VARCHAR(255) NOT NULL COMMENT '用例名称',
    description TEXT COMMENT '用例描述',
    platform VARCHAR(20) NOT NULL COMMENT '平台类型：ANDROID, IOS, HARMONYOS',
    app_id VARCHAR(64) COMMENT '关联的应用ID',
    case_type VARCHAR(50) COMMENT '用例类型：FUNCTIONAL, STABILITY, PERFORMANCE, etc.',
    steps TEXT COMMENT '测试步骤（JSON格式）',
    expected_result TEXT COMMENT '预期结果',
    priority INT DEFAULT 5 COMMENT '优先级：1-10，数字越大优先级越高',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE, INACTIVE, DEPRECATED',
    created_by VARCHAR(100) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_platform (platform),
    INDEX idx_app_id (app_id),
    INDEX idx_case_type (case_type),
    INDEX idx_status (status),
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例表';

-- 测试计划表
CREATE TABLE IF NOT EXISTS test_plans (
    id VARCHAR(64) PRIMARY KEY COMMENT '计划ID',
    name VARCHAR(255) NOT NULL COMMENT '计划名称',
    description TEXT COMMENT '计划描述',
    cron_expression VARCHAR(100) NOT NULL COMMENT 'Cron表达式，如：0 0 2 * * ? (每天凌晨2点)',
    timezone VARCHAR(50) DEFAULT 'Asia/Shanghai' COMMENT '时区',
    device_id VARCHAR(64) COMMENT '关联的设备ID',
    app_id VARCHAR(64) COMMENT '关联的应用ID',
    scenario_id VARCHAR(64) NULL COMMENT '场景ID(如login_android)，与case_ids二选一',
    scenario_config TEXT NULL COMMENT '场景执行时的config JSON',
    case_ids TEXT COMMENT '关联的用例ID列表（JSON数组格式）',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE, PAUSED, COMPLETED',
    last_run_time DATETIME COMMENT '最后执行时间',
    next_run_time DATETIME COMMENT '下次执行时间',
    run_count INT DEFAULT 0 COMMENT '执行次数',
    created_by VARCHAR(100) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_device_id (device_id),
    INDEX idx_app_id (app_id),
    INDEX idx_enabled (enabled),
    INDEX idx_status (status),
    INDEX idx_next_run_time (next_run_time),
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL,
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试计划表';

-- 测试报告表
CREATE TABLE IF NOT EXISTS test_reports (
    id VARCHAR(64) PRIMARY KEY COMMENT '报告ID',
    plan_id VARCHAR(64) COMMENT '关联的计划ID',
    case_id VARCHAR(64) COMMENT '关联的用例ID',
    device_id VARCHAR(64) COMMENT '关联的设备ID',
    app_id VARCHAR(64) COMMENT '关联的应用ID',
    execution_id VARCHAR(64) COMMENT '关联的执行ID（task_executions表）',
    status VARCHAR(20) NOT NULL COMMENT '执行状态：PASSED, FAILED, SKIPPED, RUNNING',
    start_time DATETIME COMMENT '开始时间',
    end_time DATETIME COMMENT '结束时间',
    duration BIGINT COMMENT '执行时长（毫秒）',
    screenshot_paths TEXT COMMENT '截图路径列表（JSON数组格式）',
    error_message TEXT COMMENT '错误信息',
    test_data TEXT COMMENT '测试数据（JSON格式）',
    report_path VARCHAR(500) COMMENT '报告文件路径',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
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

-- 页面对象模型表（POM）
CREATE TABLE IF NOT EXISTS page_objects (
    id VARCHAR(64) PRIMARY KEY COMMENT '页面对象ID',
    name VARCHAR(255) NOT NULL COMMENT '页面对象名称',
    description TEXT COMMENT '描述',
    platform VARCHAR(20) NOT NULL COMMENT '平台类型：ANDROID, IOS, HARMONYOS',
    app_id VARCHAR(64) COMMENT '关联的应用ID',
    page_path VARCHAR(500) COMMENT '页面路径或标识',
    catalog VARCHAR(32) NOT NULL DEFAULT 'AUTOMATION' COMMENT '元素目录：AUTOMATION / ONLINE / SHARED',
    elements TEXT COMMENT '页面元素定义（JSON格式）',
    created_by VARCHAR(100) COMMENT '创建人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_platform (platform),
    INDEX idx_app_id (app_id),
    INDEX idx_catalog (catalog),
    INDEX idx_catalog_platform_app (catalog, platform, app_id),
    INDEX idx_platform_app_page (platform, app_id, page_path(100)) COMMENT '按平台+应用+页面查 POM 时加速',
    FOREIGN KEY (app_id) REFERENCES apps(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='页面对象模型表';
