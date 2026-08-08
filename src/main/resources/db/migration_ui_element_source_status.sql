-- 元素库：双源对比 + 页面归属 + 选用状态（不新建表，扩展 ui_elements）
-- source: HUMAN_AI（人机协同）/ SOURCE_CODE（源码解析）/ IMPORT_LEGACY（旧 POM 导入）
-- status: CANDIDATE（候选）/ ACTIVE（选用）/ DEPRECATED（弃用）
-- page_id: 如 P08_MINE_SETTINGS，与采集账本 pageId 对齐

ALTER TABLE ui_elements
    ADD COLUMN page_id VARCHAR(128) NULL COMMENT '页面ID，如 P08_MINE_SETTINGS' AFTER module_tag,
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'HUMAN_AI' COMMENT 'HUMAN_AI / SOURCE_CODE / IMPORT_LEGACY' AFTER page_id,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE / ACTIVE / DEPRECATED' AFTER source,
    ADD COLUMN last_verified_at DATETIME NULL COMMENT '最近人工/自动化核实时间' AFTER status;

-- 同应用同平台同英文名可存多源各一行，便于对比
ALTER TABLE ui_elements DROP INDEX uk_app_plat_en;
ALTER TABLE ui_elements
    ADD UNIQUE KEY uk_app_plat_en_source (app_id, platform, name_en, source);

ALTER TABLE ui_elements
    ADD INDEX idx_page (app_id, platform, page_id),
    ADD INDEX idx_source (source),
    ADD INDEX idx_status (status);
