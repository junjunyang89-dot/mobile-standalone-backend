-- H5 页面目录：汇总可逐步补充的 Web 页，供预览/后续元素采集
CREATE TABLE IF NOT EXISTS ui_h5_pages (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL COMMENT '展示名，如：社区帖详情',
    url VARCHAR(1024) NOT NULL COMMENT '原始 URL（可不含 token）',
    app_id VARCHAR(64) DEFAULT NULL COMMENT '关联应用字典，如 DOMESTIC_ESPORTS',
    module_tag VARCHAR(64) DEFAULT NULL COMMENT '模块标签，如 社区/赛事',
    description VARCHAR(512) DEFAULT NULL,
    auth_type VARCHAR(32) DEFAULT 'TOKEN_QUERY' COMMENT 'NONE|TOKEN_QUERY|MANUAL',
    auth_key VARCHAR(64) DEFAULT 'token' COMMENT 'query 参数名或说明用 cookie 名',
    auth_token VARCHAR(512) DEFAULT NULL COMMENT '登录 token，仅本地库，勿提交到 Git',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 100,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_app (app_id),
    INDEX idx_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='H5页面汇总';
