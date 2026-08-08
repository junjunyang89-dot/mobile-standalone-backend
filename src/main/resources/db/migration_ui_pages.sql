-- 页面/弹窗字典：元素按 page_id 归属，列表按页折叠展示
CREATE TABLE IF NOT EXISTS ui_pages (
    id VARCHAR(64) PRIMARY KEY COMMENT '内部主键',
    app_id VARCHAR(64) NOT NULL COMMENT '应用ID',
    page_id VARCHAR(128) NOT NULL COMMENT '页面编码，如 PRIVACY_WARM_TIP',
    name_zh VARCHAR(128) NOT NULL COMMENT '展示名，如 温馨提示弹窗',
    description VARCHAR(512) NULL COMMENT '说明',
    module_tag VARCHAR(64) NULL COMMENT '所属模块，如 登录',
    sort_order INT DEFAULT 0,
    enabled TINYINT(1) DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_page (app_id, page_id),
    INDEX idx_app (app_id),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='UI页面/弹窗字典';

-- 若 locator_fallbacks 尚未添加可一并执行（已存在会报错可忽略）
-- ALTER TABLE ui_elements ADD COLUMN locator_fallbacks TEXT NULL COMMENT '备用定位 JSON' AFTER locator_value;
