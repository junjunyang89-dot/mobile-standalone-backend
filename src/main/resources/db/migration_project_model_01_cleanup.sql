-- =====================================================================
-- 项目归属模型 · 阶段 01：数据清洗 + 建立约束
-- =====================================================================
-- 本脚本已按【真实盘点结果】重写，不再是通用模板。
-- 盘点时间：2026-08-30，库：118.25.144.133:13306/mobile_standalone（MySQL 8.4.11）
-- 盘点脚本：inventory_project_model.sql
--
-- 起点：34 个项目 / 0 个外键 / 23 个 category 为空 / 11 个 category 被污染
-- 终点：27 个项目 / 11 个外键 / category 全部为合法 key
--
-- 【前置】备份：
--   mysqldump -h 118.25.144.133 -P 13306 -u root -p mobile_standalone > backup_$(date +%F).sql
--
-- 执行顺序不可调换：先解引用 → 再删项目 → 再改名 → 最后加外键。
-- 外键一旦建上，RESTRICT 会挡住删除、CASCADE 会改变改名行为。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 第 1 步：空串归一为 NULL
-- ---------------------------------------------------------------------
-- 外键允许 NULL，但不允许 ''（空串会被当成一个不存在的 code）。
--
-- ⚠ api_test_collections / api_url_entries / api_endpoints 的 project 列是 NOT NULL，
--   对它们置 NULL 会报「Column 'project' cannot be null」。本库实测这三张表都没有
--   空串行（下面几条影响 0 行），所以能安全跑过；换一个库跑之前先确认，
--   真有空串行的话要给它指一个真实项目、或删掉该行，而不是置 NULL。
UPDATE api_test_cases        SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_test_collections  SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_url_entries       SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_change_reports    SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_table_schemas     SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_code_dirs         SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_test_schedules    SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE api_endpoints         SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE requirement_endpoints SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE knowledge_handbook    SET project = NULL WHERE project IS NOT NULL AND TRIM(project) = '';
UPDATE requirement_bugs      SET project_code = NULL WHERE project_code IS NOT NULL AND TRIM(project_code) = '';


-- ---------------------------------------------------------------------
-- 第 2 步：孤儿「本地目录对比」→ NULL
-- ---------------------------------------------------------------------
-- 6 行 api_change_reports。这是 ApiChangeController.analyzeDirs 在项目为空时
-- 把【默认展示名】当成项目码存进了 project 列——是个 bug，代码要一并改（见文末）。
-- 报告内容保留，只解除错误的项目归属。
UPDATE api_change_reports SET project = NULL WHERE project = '本地目录对比';


-- ---------------------------------------------------------------------
-- 第 3 步：解除待删项目的引用
-- ---------------------------------------------------------------------
-- 待删的 7 个项目里，有 2 个带引用：
--   OVERSEAS_SERVICE_HOST_AND_TOOL_URL → knowledge_handbook 2 行
--   PogPog-H5                          → knowledge_handbook 1 行
-- 手册条目是你写的内容，【保留】，只解除归属。删项目不该连带删手册。
UPDATE knowledge_handbook
SET project = NULL
WHERE project IN ('OVERSEAS_SERVICE_HOST_AND_TOOL_URL', 'PogPog-H5');


-- ---------------------------------------------------------------------
-- 第 4 步：删除 7 个项目
-- ---------------------------------------------------------------------
-- 重复录入（与规范项目撞车，且零引用）：
--   activity-center         name=app_activity-center        ← 与 app-activity-center 重复
--   event-center            name=app_event-center           ← 与 app-event 重复
--   steam-admin             name=steam-admin                ← 与 app-admin 重复
--   APP_iOS                 name=电竞iOS客户端              ← 与 ios-domestic-esports 重复，且 kind 错标 BACKEND
--   deadlock-video-recorder name=app_deadlock-video-recorder ← 未归类，零引用
-- 确认不再需要（引用已在第 3 步解除）：
--   OVERSEAS_SERVICE_HOST_AND_TOOL_URL
--   PogPog-H5
DELETE FROM api_projects WHERE code IN (
    'activity-center',
    'event-center',
    'steam-admin',
    'APP_iOS',
    'deadlock-video-recorder',
    'OVERSEAS_SERVICE_HOST_AND_TOOL_URL',
    'PogPog-H5'
);

-- 验收：应为 27
SELECT COUNT(*) AS 剩余项目数_应为27 FROM api_projects;


-- ---------------------------------------------------------------------
-- 第 5 步：code 规范化改名
-- ---------------------------------------------------------------------
-- 此时【还没有外键】，所以必须手工同步 11 张引用表。
-- 用映射表统一驱动，避免 7 × 11 = 77 条 UPDATE 里漏写。
-- 用 TEMPORARY：普通 CREATE/DROP TABLE 在 MySQL 里会触发【隐式提交】，
-- 那样整段迁移就没法放进一个事务里做空跑验证了。临时表还能随会话自动清理。
DROP TEMPORARY TABLE IF EXISTS _project_code_map;
CREATE TEMPORARY TABLE _project_code_map (
    legacy    VARCHAR(64) NOT NULL PRIMARY KEY,
    canonical VARCHAR(64) NOT NULL,
    reason    VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO _project_code_map (legacy, canonical, reason) VALUES
    -- 主力项目：519 行引用（258 用例 + 258 接口 + …）。库里已有 1 行写成 app-data-center，
    -- 说明规范化方向数据里已经开始了。改名后两者合流。
    ('data-center',                'app-data-center',       '统一到 app- 前缀'),
    ('task-center',                'app-task-center',       '缺 app- 前缀'),
    ('app_esports_api',            'app-esports-api',       '下划线写法'),
    ('comment-service',            'app-message-center',    '服务改名'),
    -- 电竞三端：盘点确认旧码存在、新码空闲，走改名分支
    ('ios-domestic-esports',       'app-esports-ios',       '客户端规范化'),
    ('android-domestic-esports',   'app-esports-android',   '客户端规范化'),
    ('harmonyos-domestic-esports', 'app-esports-harmonyos', '客户端规范化');

-- ★ 执行前先看一眼要改什么
SELECT legacy AS 旧码, canonical AS 新码, reason AS 原因 FROM _project_code_map ORDER BY legacy;

-- 5a. 先改 11 张引用表
UPDATE api_test_cases        t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_test_collections  t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_url_entries       t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_change_reports    t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_table_schemas     t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_code_dirs         t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_test_schedules    t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE api_endpoints         t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE requirement_endpoints t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE knowledge_handbook    t JOIN _project_code_map m ON t.project = m.legacy      SET t.project = m.canonical;
UPDATE requirement_bugs      t JOIN _project_code_map m ON t.project_code = m.legacy SET t.project_code = m.canonical;

-- 5b. 再改主表
UPDATE api_projects p JOIN _project_code_map m ON p.code = m.legacy SET p.code = m.canonical;

-- 5c. 顺手把 name 列理干净。
--     盘点发现 name 大量是 code 的复制品（app-seckill 的 name 是 app_seckill，
--     comment-service 的 name 是 app_comment-service）。展示名要读 name 列，
--     所以它必须是给人看的，不能是另一份 code。
-- 顺序不能反：通用规则必须【先】跑。
-- 排序规则是 utf8mb4_unicode_ci（大小写不敏感），所以 name = code 这个条件
-- 会把 'APP-ESPORTS-iOS' 也判成「等于 app-esports-ios」，
-- 放在后面会把下面精心设好的展示名重新覆盖成 UPPER(code)。
UPDATE api_projects
SET name = UPPER(code)
WHERE (name = code OR name = REPLACE(code, '-', '_'))
  AND code LIKE 'app-%';

-- 再逐个覆盖需要特殊写法的展示名
UPDATE api_projects SET name = 'APP-DATA-CENTER（数据中心）' WHERE code = 'app-data-center';
UPDATE api_projects SET name = 'APP-TASK-CENTER'            WHERE code = 'app-task-center';
UPDATE api_projects SET name = 'APP-ESPORTS-API'            WHERE code = 'app-esports-api';
UPDATE api_projects SET name = 'APP-MESSAGE-CENTER'         WHERE code = 'app-message-center';
-- iOS 的小写 i 只能存在 name 列里：从 code 推导必然被 toUpperCase 变成 IOS
UPDATE api_projects SET name = 'APP-ESPORTS-iOS'            WHERE code = 'app-esports-ios';
UPDATE api_projects SET name = 'APP-ESPORTS-ANDROID'        WHERE code = 'app-esports-android';
UPDATE api_projects SET name = 'APP-ESPORTS-HARMONYOS'      WHERE code = 'app-esports-harmonyos';

DROP TEMPORARY TABLE _project_code_map;


-- ---------------------------------------------------------------------
-- 第 6 步：统一列类型
-- ---------------------------------------------------------------------
-- requirement_bugs.project_code 是 VARCHAR(128)，其余都是 VARCHAR(64)。
-- 外键要求两侧类型完全一致。盘点【12】确认无超长值，可安全收窄。
ALTER TABLE requirement_bugs
    MODIFY COLUMN project_code VARCHAR(64)
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
    DEFAULT NULL COMMENT '归属项目，FK → api_projects.code';


-- ---------------------------------------------------------------------
-- 第 7 步：孤儿复检（加外键前的最后一道闸）
-- ---------------------------------------------------------------------
-- 【必须返回 0 行】才能继续。有残留说明前面某步漏了，别硬加外键。
WITH usage_all AS (
    SELECT 'api_test_cases'        AS tbl, project      AS project_code FROM api_test_cases
    UNION ALL SELECT 'api_test_collections',  project      FROM api_test_collections
    UNION ALL SELECT 'api_url_entries',       project      FROM api_url_entries
    UNION ALL SELECT 'api_change_reports',    project      FROM api_change_reports
    UNION ALL SELECT 'api_table_schemas',     project      FROM api_table_schemas
    UNION ALL SELECT 'api_code_dirs',         project      FROM api_code_dirs
    UNION ALL SELECT 'api_test_schedules',    project      FROM api_test_schedules
    UNION ALL SELECT 'api_endpoints',         project      FROM api_endpoints
    UNION ALL SELECT 'requirement_endpoints', project      FROM requirement_endpoints
    UNION ALL SELECT 'knowledge_handbook',    project      FROM knowledge_handbook
    UNION ALL SELECT 'requirement_bugs',      project_code FROM requirement_bugs
)
SELECT u.tbl AS 表, u.project_code AS 孤儿值, COUNT(*) AS 行数
FROM usage_all u
LEFT JOIN api_projects p ON p.code = u.project_code
WHERE u.project_code IS NOT NULL AND TRIM(u.project_code) <> '' AND p.code IS NULL
GROUP BY u.tbl, u.project_code;


-- ---------------------------------------------------------------------
-- 第 8 步：加外键
-- ---------------------------------------------------------------------
-- RESTRICT：项目底下还有数据时不允许删项目。
-- CASCADE ：将来改 api_projects.code，11 张引用表自动同步（本次改名已手工做完，
--           但以后再规范化 code 就不用再写 11 条 UPDATE 了）。
ALTER TABLE api_test_cases        ADD CONSTRAINT fk_atc_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_test_collections  ADD CONSTRAINT fk_atcol_project FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_url_entries       ADD CONSTRAINT fk_aue_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_change_reports    ADD CONSTRAINT fk_acr_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_table_schemas     ADD CONSTRAINT fk_ats_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_code_dirs         ADD CONSTRAINT fk_acd_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_test_schedules    ADD CONSTRAINT fk_atsch_project FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE api_endpoints         ADD CONSTRAINT fk_aep_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE requirement_endpoints ADD CONSTRAINT fk_rep_project   FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE knowledge_handbook    ADD CONSTRAINT fk_kh_project    FOREIGN KEY (project)      REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE requirement_bugs      ADD CONSTRAINT fk_rb_project    FOREIGN KEY (project_code) REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE;


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS 已建外键数_应为11
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 'api_projects';

SELECT COUNT(*) AS 项目数_应为27 FROM api_projects;

-- 主力项目改名后引用应完整迁移（原 data-center 519 行）
SELECT 'app-data-center' AS code,
       (SELECT COUNT(*) FROM api_test_cases WHERE project = 'app-data-center') AS 用例,
       (SELECT COUNT(*) FROM api_endpoints  WHERE project = 'app-data-center') AS 接口;


-- =====================================================================
-- 配套代码改动（本阶段必须同步，否则应用启动会把旧码写回来）
-- =====================================================================
-- 1) ★ data-center 改名的连带修改（4 处）——不改的话 seedIfEmpty 会重新造出
--    一个 data-center 项目，和 app-data-center 并存：
--      ApiTestCaseService:35    PROJECT_DATA_CENTER = "data-center" → "app-data-center"
--      ApiTestController:310    种子重导 switch 分支（用的是常量，改常量即可）
--      ApiUrlService:47,56      ensureExists / countByProject（同样走常量）
--      ApiProjectService:99     seedIfEmpty 的 setCode（同样走常量）
--    实际只需改常量值一处，其余引用自动跟随。
--
-- 2) ★ ApiChangeController.analyzeDirs 的 bug：项目为空时把「本地目录对比」
--    这个展示名当项目码存进了 project 列。应传 null，展示名只用于报告标题。
--
-- 3) ApiTestCaseService.createCase / updateCase 加 project 存在性校验。
--    否则用户拿到的是一句 MySQL 外键报错，看不懂也不知道去哪修。
--    文案要指路：「项目 xxx 不存在，请先到 项目管理 创建」。
--
-- 4) ApiAppCatalog.LEGACY_ALIASES 瘦身：数据已归一，运行时不再需要兜底。
--    注意 'data-center' → 'app-data-center' 这条要【删掉】，
--    否则 canonicalCode 会把新码再映射一次。
-- =====================================================================


-- =====================================================================
-- 回滚
-- =====================================================================
-- 外键、列宽可逆：
--   ALTER TABLE api_test_cases DROP FOREIGN KEY fk_atc_project;   -- 其余 10 张同理
--   ALTER TABLE requirement_bugs MODIFY COLUMN project_code VARCHAR(128) DEFAULT NULL;
-- 【删除与改名不可逆】——只能从 mysqldump 备份恢复。
-- 这就是为什么第 4/5 步前必须有备份。
-- =====================================================================
