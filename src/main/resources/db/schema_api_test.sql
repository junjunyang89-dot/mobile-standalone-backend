-- 接口测试：环境 + 用例 + URL 管理（也可由服务启动自动建表）
CREATE TABLE IF NOT EXISTS api_test_envs (
    environment VARCHAR(16) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    default_headers TEXT NULL,
    default_token VARCHAR(512) NULL,
    enabled TINYINT(1) DEFAULT 1,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_url_entries (
    id VARCHAR(64) PRIMARY KEY,
    project VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'TEST',
    base_url VARCHAR(1024) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 100,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project_env (project, environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_test_cases (
    id VARCHAR(64) PRIMARY KEY,
    project VARCHAR(64) DEFAULT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    requirement_ref VARCHAR(255) DEFAULT NULL,
    method VARCHAR(16) NOT NULL DEFAULT 'GET',
    path VARCHAR(1024) NOT NULL,
    url_id VARCHAR(64) DEFAULT NULL,
    query TEXT NULL,
    headers TEXT NULL,
    body TEXT NULL,
    assertions TEXT NOT NULL,
    field_notes TEXT NULL,
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 100,
    created_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled_sort (enabled, sort_order),
    INDEX idx_project (project)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_test_runs (
    id VARCHAR(64) PRIMARY KEY,
    environment VARCHAR(16) NOT NULL,
    total INT NOT NULL DEFAULT 0,
    passed INT NOT NULL DEFAULT 0,
    failed INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    ok TINYINT(1) NOT NULL DEFAULT 0,
    message VARCHAR(512) DEFAULT NULL,
    created_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_test_run_cases (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(64) DEFAULT NULL,
    name VARCHAR(255) DEFAULT NULL,
    method VARCHAR(16) DEFAULT NULL,
    path VARCHAR(1024) DEFAULT NULL,
    url VARCHAR(2048) DEFAULT NULL,
    status VARCHAR(32) NOT NULL,
    http_status INT DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    error VARCHAR(1024) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_run (run_id),
    INDEX idx_created (created_at),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
