-- page_objects：增加元素目录分类 catalog
-- AUTOMATION=自动化元素（页面对象页默认）; ONLINE=线上回归专用; SHARED=两边共用
-- 已有库执行本脚本；若列已存在会报 Duplicate column，可忽略。

ALTER TABLE page_objects
    ADD COLUMN catalog VARCHAR(32) NOT NULL DEFAULT 'AUTOMATION'
        COMMENT '元素目录：AUTOMATION / ONLINE / SHARED'
        AFTER page_path;

UPDATE page_objects SET catalog = 'AUTOMATION' WHERE catalog IS NULL OR catalog = '';

ALTER TABLE page_objects
    ADD INDEX idx_catalog (catalog);

ALTER TABLE page_objects
    ADD INDEX idx_catalog_platform_app (catalog, platform, app_id);
