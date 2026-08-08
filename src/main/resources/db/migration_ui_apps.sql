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

-- ============ 蒲公英 appKey ============
-- buildKey 每次发版都变，写死在流程里意味着每次发版前都要改一遍流程。
-- 存 appKey 之后，安装步骤按「应用 + 平台」自动取最新版，流程一次配好不用再动。
-- 同一个 appKey 下可能同时有 Android 和 iOS 包，按平台各存一个更明确。
ALTER TABLE ui_apps
  ADD COLUMN pgyer_app_key_android VARCHAR(64) NULL COMMENT '蒲公英 Android appKey' AFTER ios_bundle_id,
  ADD COLUMN pgyer_app_key_ios VARCHAR(64) NULL COMMENT '蒲公英 iOS appKey' AFTER pgyer_app_key_android;
