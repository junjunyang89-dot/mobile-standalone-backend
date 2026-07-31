-- 若已执行过旧版 schema_ui_assets.sql，可单独跑本脚本补应用字典表
CREATE TABLE IF NOT EXISTS ui_apps (
    id VARCHAR(64) PRIMARY KEY COMMENT '应用编码，如 DOMESTIC_ESPORTS',
    name_zh VARCHAR(128) NOT NULL COMMENT '显示名称',
    description VARCHAR(512) COMMENT '说明',
    android_package VARCHAR(255) COMMENT 'Android 包名',
    ios_bundle_id VARCHAR(255) COMMENT 'iOS Bundle ID',
    enabled TINYINT(1) DEFAULT 1,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='业务应用字典';
