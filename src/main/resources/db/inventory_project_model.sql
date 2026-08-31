-- =====================================================================
-- 项目归属模型 · 只读盘点
-- =====================================================================
-- 用途：在动任何数据之前，把「库里到底有什么」查清楚。
--
-- 这份脚本【只有 SELECT，不会修改任何数据】，可以在生产库直接跑。
--
-- 为什么必须先跑它：代码里那几份硬编码清单（ApiProjectCatalog.PVP_CODES /
-- WEB_CODES、ApiProjectService.CLIENT_PROJECT_CODES）已被证明不可靠——
-- 里面既有从未存在过的占位（overseas_service_host_and_tool_url），
-- 也有没有任何逻辑会创建的客户端码（ios-domestic-esports）。
-- 迁移清单必须以本脚本的输出为准，不能以代码为准。
--
-- 跑法：
--   mysql -u root -p mobile_standalone < inventory_project_model.sql
-- 或在客户端里分段执行，逐段看结果。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 【0】表存在性：确认 11 张引用表都在
-- ---------------------------------------------------------------------
-- 这些表由各 Service 的 @PostConstruct 懒建。若应用成功启动过，应该都在。
-- 缺表的话，下面对应段落的查询要删掉，否则会报错。
SELECT
    t.want                            AS table_name,
    IF(s.TABLE_NAME IS NULL, '缺失', '存在') AS status
FROM (
    SELECT 'api_projects'           AS want UNION ALL
    SELECT 'api_project_categories'       UNION ALL
    SELECT 'api_test_cases'               UNION ALL
    SELECT 'api_test_collections'         UNION ALL
    SELECT 'api_url_entries'              UNION ALL
    SELECT 'api_change_reports'           UNION ALL
    SELECT 'api_table_schemas'            UNION ALL
    SELECT 'api_code_dirs'                UNION ALL
    SELECT 'api_test_schedules'           UNION ALL
    SELECT 'api_endpoints'                UNION ALL
    SELECT 'requirement_endpoints'        UNION ALL
    SELECT 'knowledge_handbook'           UNION ALL
    SELECT 'requirement_bugs'
) t
LEFT JOIN information_schema.TABLES s
       ON s.TABLE_SCHEMA = DATABASE() AND s.TABLE_NAME = t.want
ORDER BY status DESC, table_name;


-- ---------------------------------------------------------------------
-- 【1】项目主表全量 —— 这是唯一的事实来源
-- ---------------------------------------------------------------------
-- 重点看：category 有几个是空的（空＝当前靠代码推断，迁移后会掉进 OTHER）
SELECT
    code,
    name,
    IFNULL(kind, '(空→按BACKEND)')  AS kind,
    IFNULL(category, '(空→靠推断)') AS category,
    enabled,
    sort_order,
    IF(repo_path IS NULL OR repo_path = '', '否', '是') AS 可做变更分析
FROM api_projects
ORDER BY IFNULL(category, 'ZZZ'), sort_order, code;


-- 项目总数与 category 填充率
SELECT
    COUNT(*)                                                          AS 项目总数,
    SUM(category IS NOT NULL AND TRIM(category) <> '')                AS 已配分类,
    SUM(category IS NULL OR TRIM(category) = '')                      AS 未配分类,
    SUM(kind = 'CLIENT')                                              AS 客户端项目,
    SUM(kind IS NULL OR kind = '' OR kind = 'BACKEND')                AS 后端项目
FROM api_projects;


-- ---------------------------------------------------------------------
-- 【2】分类注册表现状
-- ---------------------------------------------------------------------
SELECT cat_key, label, sort_order, builtin
FROM api_project_categories
ORDER BY sort_order, cat_key;


-- ---------------------------------------------------------------------
-- 【3】project 值在各引用表的实际分布
-- ---------------------------------------------------------------------
-- 回答「这个项目码到底有没有人在用」。数字大的不能轻易改名。
WITH usage_all AS (
    SELECT 'api_test_cases'        AS tbl, project      AS project_code, COUNT(*) AS n FROM api_test_cases        GROUP BY project
    UNION ALL SELECT 'api_test_collections',  project,      COUNT(*) FROM api_test_collections  GROUP BY project
    UNION ALL SELECT 'api_url_entries',       project,      COUNT(*) FROM api_url_entries       GROUP BY project
    UNION ALL SELECT 'api_change_reports',    project,      COUNT(*) FROM api_change_reports    GROUP BY project
    UNION ALL SELECT 'api_table_schemas',     project,      COUNT(*) FROM api_table_schemas     GROUP BY project
    UNION ALL SELECT 'api_code_dirs',         project,      COUNT(*) FROM api_code_dirs         GROUP BY project
    UNION ALL SELECT 'api_test_schedules',    project,      COUNT(*) FROM api_test_schedules    GROUP BY project
    UNION ALL SELECT 'api_endpoints',         project,      COUNT(*) FROM api_endpoints         GROUP BY project
    UNION ALL SELECT 'requirement_endpoints', project,      COUNT(*) FROM requirement_endpoints GROUP BY project
    UNION ALL SELECT 'knowledge_handbook',    project,      COUNT(*) FROM knowledge_handbook    GROUP BY project
    UNION ALL SELECT 'requirement_bugs',      project_code, COUNT(*) FROM requirement_bugs      GROUP BY project_code
)
SELECT
    project_code,
    SUM(n)                                        AS 引用总行数,
    COUNT(DISTINCT tbl)                           AS 涉及表数,
    GROUP_CONCAT(CONCAT(tbl, ':', n) ORDER BY n DESC SEPARATOR ' | ') AS 明细
FROM usage_all
WHERE project_code IS NOT NULL AND TRIM(project_code) <> ''
GROUP BY project_code
ORDER BY 引用总行数 DESC;


-- ---------------------------------------------------------------------
-- 【4】孤儿检测 —— 被引用但 api_projects 里没有的 code
-- ---------------------------------------------------------------------
-- 【阶段 01 加外键前必须清零】。每一行都要人工判断：
--   是笔误（→ 归一到正确 code） / 是漏建的项目（→ 补建） / 是废弃数据（→ 删）
WITH usage_all AS (
    SELECT 'api_test_cases'        AS tbl, project      AS project_code, COUNT(*) AS n FROM api_test_cases        GROUP BY project
    UNION ALL SELECT 'api_test_collections',  project,      COUNT(*) FROM api_test_collections  GROUP BY project
    UNION ALL SELECT 'api_url_entries',       project,      COUNT(*) FROM api_url_entries       GROUP BY project
    UNION ALL SELECT 'api_change_reports',    project,      COUNT(*) FROM api_change_reports    GROUP BY project
    UNION ALL SELECT 'api_table_schemas',     project,      COUNT(*) FROM api_table_schemas     GROUP BY project
    UNION ALL SELECT 'api_code_dirs',         project,      COUNT(*) FROM api_code_dirs         GROUP BY project
    UNION ALL SELECT 'api_test_schedules',    project,      COUNT(*) FROM api_test_schedules    GROUP BY project
    UNION ALL SELECT 'api_endpoints',         project,      COUNT(*) FROM api_endpoints         GROUP BY project
    UNION ALL SELECT 'requirement_endpoints', project,      COUNT(*) FROM requirement_endpoints GROUP BY project
    UNION ALL SELECT 'knowledge_handbook',    project,      COUNT(*) FROM knowledge_handbook    GROUP BY project
    UNION ALL SELECT 'requirement_bugs',      project_code, COUNT(*) FROM requirement_bugs      GROUP BY project_code
)
SELECT
    u.project_code                                AS 孤儿值,
    SUM(u.n)                                      AS 引用行数,
    GROUP_CONCAT(CONCAT(u.tbl, ':', u.n) ORDER BY u.n DESC SEPARATOR ' | ') AS 出现在
FROM usage_all u
LEFT JOIN api_projects p ON p.code = u.project_code
WHERE u.project_code IS NOT NULL
  AND TRIM(u.project_code) <> ''
  AND p.code IS NULL
GROUP BY u.project_code
ORDER BY 引用行数 DESC;


-- ---------------------------------------------------------------------
-- 【5】迁移目标 code 的存在性 —— 决定「改名」还是「新建」
-- ---------------------------------------------------------------------
-- 电竞三端：旧码在库里 → 改名；不在 → 直接新建
SELECT
    chk.old_code                                        AS 旧code,
    chk.new_code                                        AS 新code,
    IF(o.code IS NULL, '不存在', '存在')                 AS 旧码状态,
    IF(n.code IS NULL, '不存在', '已存在(冲突!)')        AS 新码状态,
    CASE
        WHEN n.code IS NOT NULL THEN '⚠ 新码已被占用，先人工处理'
        WHEN o.code IS NOT NULL THEN '改名：UPDATE 主表 + 11 张引用表'
        ELSE                          '新建：INSERT 一条即可'
    END                                                 AS 应执行操作
FROM (
    SELECT 'ios-domestic-esports'       AS old_code, 'app-esports-ios'       AS new_code UNION ALL
    SELECT 'android-domestic-esports',                'app-esports-android'              UNION ALL
    SELECT 'harmonyos-domestic-esports',              'app-esports-harmonyos'
) chk
LEFT JOIN api_projects o ON o.code = chk.old_code
LEFT JOIN api_projects n ON n.code = chk.new_code;


-- 只换分类、不改 code 的那批：确认它们是否真的存在
SELECT
    chk.code                                AS 项目码,
    chk.target_category                     AS 目标分类,
    chk.target_kind                         AS 目标kind,
    IF(p.code IS NULL, '不存在(跳过)', '存在') AS 库中状态,
    IFNULL(p.category, '(空)')              AS 当前分类,
    IFNULL(p.kind, '(空)')                  AS 当前kind
FROM (
    SELECT 'app-steam-web'          AS code, 'APP'    AS target_category, 'BACKEND' AS target_kind UNION ALL
    SELECT 'steam-web',                      'APP',                      'BACKEND'                UNION ALL
    SELECT 'steam-admin',                    'APP',                      'BACKEND'                UNION ALL
    SELECT 'android-overseas-pogpog',        'POGPOG',                   'CLIENT'                 UNION ALL
    SELECT 'ios-overseas-pogpog',            'POGPOG',                   'CLIENT'                 UNION ALL
    SELECT 'pogpog-h5',                      'POGPOG',                   'H5'                     UNION ALL
    SELECT 'app-global-agent',               'POGPOG',                   'BACKEND'                UNION ALL
    SELECT 'app-global-data',                'POGPOG',                   'BACKEND'
) chk
LEFT JOIN api_projects p ON p.code = chk.code
ORDER BY chk.target_category, chk.code;


-- ---------------------------------------------------------------------
-- 【6】global 模糊盘点 —— 找出代码里没登记的 global 项目
-- ---------------------------------------------------------------------
-- 代码里只硬编码了 app-global-agent 和 app-global-data。
-- 你提到的 global-video 不在其中，说明库里可能还有别的。
-- 【重要】结果需人工确认：可能有名字含 global 但实际属于国内的项目，不能盲迁。
SELECT
    code,
    name,
    IFNULL(category, '(空)') AS 当前分类,
    IFNULL(kind, '(空)')     AS 当前kind,
    '建议 → POGPOG'          AS 建议
FROM api_projects
WHERE LOWER(code) LIKE '%global%'
   OR LOWER(IFNULL(name, '')) LIKE '%global%'
ORDER BY code;


-- ---------------------------------------------------------------------
-- 【7】别名脏值检测 —— 阶段 01 归一化的实际工作量
-- ---------------------------------------------------------------------
-- 命中的都是历史遗留写法，应归一到规范 app-{gitlab} 码。
-- 注意 data-center 需单独决策（见脚本末尾说明）。
SELECT
    p.code                       AS 现有码,
    a.canonical                  AS 规范码,
    IF(t.code IS NULL, '目标码空闲', '目标码已存在(需合并!)') AS 冲突
FROM api_projects p
JOIN (
    SELECT 'data-center'      AS legacy, 'app-data-center'     AS canonical UNION ALL
    SELECT 'activity-center',           'app-activity-center'  UNION ALL
    SELECT 'task-center',               'app-task-center'      UNION ALL
    SELECT 'app-task-cneter',           'app-task-center'      UNION ALL
    SELECT 'app_task-center',           'app-task-center'      UNION ALL
    SELECT 'app_esports_api',           'app-esports-api'      UNION ALL
    SELECT 'app_activity-center',       'app-activity-center'  UNION ALL
    SELECT 'app_seckill',               'app-seckill'          UNION ALL
    SELECT 'comment-service',           'app-message-center'   UNION ALL
    SELECT 'event-center',              'app-event'            UNION ALL
    SELECT 'steam-admin',               'app-admin'
) a ON a.legacy = p.code
LEFT JOIN api_projects t ON t.code = a.canonical;


-- 裸 GitLab 名（未加 app- 前缀）的项目
SELECT
    p.code                                   AS 现有码,
    CONCAT('app-', p.code)                   AS 规范码,
    IF(t.code IS NULL, '目标码空闲', '目标码已存在(需合并!)') AS 冲突
FROM api_projects p
JOIN (
    SELECT 'seckill' AS g UNION ALL SELECT 'video'          UNION ALL SELECT 'robot'         UNION ALL
    SELECT 'xiaomai'      UNION ALL SELECT 'activity'       UNION ALL SELECT 'activity-center' UNION ALL
    SELECT 'admin'        UNION ALL SELECT 'community'      UNION ALL SELECT 'decoration'    UNION ALL
    SELECT 'engine'       UNION ALL SELECT 'event'          UNION ALL SELECT 'push'          UNION ALL
    SELECT 'ban'          UNION ALL SELECT 'user'           UNION ALL SELECT 'data-center'   UNION ALL
    SELECT 'data-agent'   UNION ALL SELECT 'data-provider'  UNION ALL SELECT 'esports-api'   UNION ALL
    SELECT 'global-agent' UNION ALL SELECT 'message-center' UNION ALL SELECT 'news-publish'  UNION ALL
    SELECT 'passport'     UNION ALL SELECT 'steam-web'      UNION ALL SELECT 'task-center'   UNION ALL
    SELECT 'vip-center'   UNION ALL SELECT 'wmpay'          UNION ALL SELECT 'wmpayplus'
) gl ON gl.g = p.code
LEFT JOIN api_projects t ON t.code = CONCAT('app-', p.code)
ORDER BY p.code;


-- ---------------------------------------------------------------------
-- 【8】加外键前的阻塞项检查
-- ---------------------------------------------------------------------
-- 8a. requirement_bugs.project_code 是 VARCHAR(128)，api_projects.code 是 VARCHAR(64)。
--     外键要求类型完全一致 → 必须先收窄到 64。收窄前确认没有超长值。
SELECT
    COUNT(*)                                     AS 超长值行数,
    IFNULL(MAX(CHAR_LENGTH(project_code)), 0)    AS 最长长度,
    IF(IFNULL(MAX(CHAR_LENGTH(project_code)), 0) > 64,
       '⚠ 有超长值，收窄会截断，先处理',
       '✓ 可安全收窄到 VARCHAR(64)')             AS 结论
FROM requirement_bugs;


-- 8b. 现有字符集/排序规则是否一致（不一致同样加不了外键）
SELECT
    TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, CHARACTER_SET_NAME, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'api_projects'    AND COLUMN_NAME = 'code')
    OR (TABLE_NAME = 'requirement_bugs' AND COLUMN_NAME = 'project_code')
    OR (COLUMN_NAME = 'project' AND TABLE_NAME IN (
          'api_test_cases','api_test_collections','api_url_entries','api_change_reports',
          'api_table_schemas','api_code_dirs','api_test_schedules','api_endpoints',
          'requirement_endpoints','knowledge_handbook')))
ORDER BY TABLE_NAME;


-- 8c. 已存在的外键（确认没有重名约束）
SELECT
    TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME,
    REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME = 'api_projects';


-- =====================================================================
-- 跑完之后要做的判断
-- =====================================================================
--
-- 【4】孤儿值      → 每一行都要定性：笔误 / 漏建 / 废弃。清零才能加外键。
-- 【5】电竞三端    → 决定各自走「改名」还是「新建」分支。
-- 【6】global 清单 → 人工确认哪些真属海外，挑掉误伤的。
-- 【7】别名脏值    → 确认要归一的清单；有「目标码已存在」的要先决定怎么合并。
-- 【8a】超长值     → 若有，先处理再收窄。
--
-- ---------------------------------------------------------------------
-- ⚠ 关于 data-center，需要你单独拍板
-- ---------------------------------------------------------------------
-- 它是【唯一】由 seedIfEmpty() 自动创建的项目，且带着 seed-data-center.json
-- 里成百条种子用例，多半是库里数据最多的项目。
--
-- 但 ApiAppCatalog 的别名表把它映射成 app-data-center，也就是说：
-- 按规范它该叫 app-data-center，而现存数据全都写着 data-center。
--
-- 改名的代价不只是数据——还要同步改代码：
--   ApiTestCaseService.PROJECT_DATA_CENTER 常量（"data-center"）
--   ApiTestController:310  种子重导的 switch 分支
--   ApiUrlService:47,56    URL 种子的 ensureExists / countByProject
--   ApiProjectService:99   seedIfEmpty 里的 setCode
--
-- 两个选择：
--   (a) 保持 data-center 不变 —— 从别名表里删掉这条映射，承认它就是规范码。
--       代价：与 app-{gitlab} 命名约定不一致，但零风险。【默认推荐】
--   (b) 改名为 app-data-center —— 数据与代码同步改，一次做干净。
--       代价：改动面最大的一次迁移，且要重跑种子验证。
--
-- 迁移脚本 01 默认按 (a) 处理，不动 data-center。
-- =====================================================================
