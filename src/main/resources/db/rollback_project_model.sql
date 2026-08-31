-- =====================================================================
-- 项目归属模型迁移 · 回滚快照
-- =====================================================================
-- 生成时间：2026-08-30，取自 mobile_standalone 库迁移【前】的真实状态。
--
-- 迁移里真正不可逆的只有 3 处，本文件把它们精确留底：
--   ① 被删除的 7 个项目
--   ② 被置 NULL 的 9 行引用（本地目录对比 6 行、海外两项 3 行）
--   ③ 被删除的 2 条垃圾分类
-- 其余（改名、分类调整、加外键、列收窄）都是可逆操作，回滚语句见文末。
--
-- 【注意】若迁移已加上外键，回滚时要按依赖顺序来：
--   先恢复 api_projects 行，再恢复引用；否则外键会拦住引用的恢复。
-- =====================================================================


-- ---------------------------------------------------------------------
-- ① 恢复被删除的 7 个项目
-- ---------------------------------------------------------------------
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('activity-center','app_activity-center','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('APP_iOS','电竞iOS客户端','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('deadlock-video-recorder','app_deadlock-video-recorder','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('event-center','app_event-center','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('OVERSEAS_SERVICE_HOST_AND_TOOL_URL','海外服务HOST和工具地址','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('PogPog-H5','海外H5页面','','BACKEND',NULL,1,100);
INSERT INTO api_projects (code,name,description,kind,category,enabled,sort_order) VALUES ('steam-admin','steam-admin','','BACKEND',NULL,1,100);


-- ---------------------------------------------------------------------
-- ② 恢复被置 NULL 的 9 行引用（行 id 是迁移前实测的）
-- ---------------------------------------------------------------------
UPDATE api_change_reports SET project = '本地目录对比' WHERE id IN (2, 3, 4, 5, 6, 7);
UPDATE knowledge_handbook SET project = 'OVERSEAS_SERVICE_HOST_AND_TOOL_URL' WHERE id IN (7, 8);
UPDATE knowledge_handbook SET project = 'PogPog-H5' WHERE id = 6;


-- ---------------------------------------------------------------------
-- ③ 恢复被删除的 2 条垃圾分类
-- ---------------------------------------------------------------------
-- 它们本来就是脏数据（label 被当成 key 存进 category 列，触发自动注册），
-- 正常情况下【不该恢复】。列在这里只为让回滚是完整的。
INSERT INTO api_project_categories (cat_key, label, sort_order, builtin) VALUES ('APP后端', 'APP后端', 500, 0);
INSERT INTO api_project_categories (cat_key, label, sort_order, builtin) VALUES ('电竞iOS客户端', '电竞iOS客户端', 500, 0);


-- =====================================================================
-- 其余可逆操作的回滚
-- =====================================================================

-- A. 删外键（要先删，否则下面的改名会被 RESTRICT 挡住）
-- ALTER TABLE api_test_cases        DROP FOREIGN KEY fk_atc_project;
-- ALTER TABLE api_test_collections  DROP FOREIGN KEY fk_atcol_project;
-- ALTER TABLE api_url_entries       DROP FOREIGN KEY fk_aue_project;
-- ALTER TABLE api_change_reports    DROP FOREIGN KEY fk_acr_project;
-- ALTER TABLE api_table_schemas     DROP FOREIGN KEY fk_ats_project;
-- ALTER TABLE api_code_dirs         DROP FOREIGN KEY fk_acd_project;
-- ALTER TABLE api_test_schedules    DROP FOREIGN KEY fk_atsch_project;
-- ALTER TABLE api_endpoints         DROP FOREIGN KEY fk_aep_project;
-- ALTER TABLE requirement_endpoints DROP FOREIGN KEY fk_rep_project;
-- ALTER TABLE knowledge_handbook    DROP FOREIGN KEY fk_kh_project;
-- ALTER TABLE requirement_bugs      DROP FOREIGN KEY fk_rb_project;

-- B. 列宽改回
-- ALTER TABLE requirement_bugs MODIFY COLUMN project_code VARCHAR(128) DEFAULT NULL;

-- C. 反向改名（若外键还在且带 ON UPDATE CASCADE，只改主表即可级联；
--    外键已删则需按迁移脚本第 5a 步的写法逐表还原）
-- UPDATE api_projects SET code = 'data-center'                WHERE code = 'app-data-center';
-- UPDATE api_projects SET code = 'task-center'                WHERE code = 'app-task-center';
-- UPDATE api_projects SET code = 'app_esports_api'            WHERE code = 'app-esports-api';
-- UPDATE api_projects SET code = 'comment-service'            WHERE code = 'app-message-center';
-- UPDATE api_projects SET code = 'ios-domestic-esports'       WHERE code = 'app-esports-ios';
-- UPDATE api_projects SET code = 'android-domestic-esports'   WHERE code = 'app-esports-android';
-- UPDATE api_projects SET code = 'harmonyos-domestic-esports' WHERE code = 'app-esports-harmonyos';

-- D. 分类还原（迁移前：11 个项目是 'APP后端'，其余 23 个为 NULL）
-- UPDATE api_projects SET category = 'APP后端' WHERE code IN (
--     'app-activity','app-activity-center','app-community','app-decoration','app-engine',
--     'app-event','app-push','app-robot','app-seckill','app-video','app-xiaomai');
-- UPDATE api_projects SET category = NULL WHERE code NOT IN (
--     'app-activity','app-activity-center','app-community','app-decoration','app-engine',
--     'app-event','app-push','app-robot','app-seckill','app-video','app-xiaomai');
-- UPDATE api_project_categories SET label = 'APP 后端',     sort_order = 10 WHERE cat_key = 'APP';
-- UPDATE api_project_categories SET label = 'PVP · 客户端', sort_order = 20 WHERE cat_key = 'PVP';
-- UPDATE api_project_categories SET label = 'WEB',          sort_order = 30 WHERE cat_key = 'WEB';
-- UPDATE api_project_categories SET label = '其他',         sort_order = 40 WHERE cat_key = 'OTHER';
-- DELETE FROM api_project_categories WHERE cat_key = 'POGPOG';

-- E. name 列还原（迁移前 name 多为 code 的复制品，价值不大，一般不必还原）
-- =====================================================================
