-- Web 端测试用例（员工页面编排，Playwright 后台执行）
CREATE TABLE IF NOT EXISTS web_test_cases (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL COMMENT '用例名称',
    description VARCHAR(1024) DEFAULT NULL COMMENT '说明',
    requirement_ref VARCHAR(255) DEFAULT NULL COMMENT '需求号/Wiki/Jira',
    channel VARCHAR(32) NOT NULL DEFAULT 'H5' COMMENT 'H5 / WEB / MINIPROGRAM',
    page_url VARCHAR(1024) DEFAULT NULL COMMENT '默认打开地址',
    h5_page_id VARCHAR(64) DEFAULT NULL COMMENT '关联 ui_h5_pages.id',
    auth_mode VARCHAR(32) DEFAULT 'none' COMMENT 'none/query/localStorage/cookie/bearer',
    auth_token VARCHAR(512) DEFAULT NULL COMMENT '默认 token，仅本地库',
    auth_query_key VARCHAR(64) DEFAULT 'token',
    steps TEXT NOT NULL COMMENT '步骤 JSON',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 100,
    created_by VARCHAR(100) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_channel (channel),
    INDEX idx_enabled_sort (enabled, sort_order),
    INDEX idx_h5 (h5_page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Web/H5 页面测试用例';
