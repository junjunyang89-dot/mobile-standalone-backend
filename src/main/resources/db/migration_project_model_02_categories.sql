-- =====================================================================
-- 项目归属模型 · 阶段 02：四条产品线重划
-- =====================================================================
-- 已按【真实盘点结果】重写。前置：阶段 01 已完成（27 个项目 / 11 个外键）。
--
-- 盘点发现的关键问题：分类表已被污染。
--   api_projects.category 里有 11 行的值是 'APP后端' —— 这是 APP 分类的
--   【label】而不是【key】。ensureCategoryRegistered() 见到未注册的值就自动
--   建了一条分类，于是 api_project_categories 里多出：
--     APP后端        sort_order=500  builtin=0
--     电竞iOS客户端  sort_order=500  builtin=0
--   树上因此会出现「APP后端」和内置的「APP 后端」两个并列分类。
--   这正是「有些都不对」最直接的来源。
--
-- 终态：APP 19 / POGPOG 3 / OTHER 5 / PVP 0 / WEB 0，共 27。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 第 1 步：清洗被污染的 category 值
-- ---------------------------------------------------------------------
-- label 写进了 key 的位置，改回合法 key。
UPDATE api_projects SET category = 'APP' WHERE category = 'APP后端';
UPDATE api_projects SET category = 'APP' WHERE category = '电竞iOS客户端';

-- 删掉自动注册出来的垃圾分类（builtin=0 且已无项目引用）
DELETE FROM api_project_categories
WHERE builtin = 0
  AND cat_key IN ('APP后端', '电竞iOS客户端');


-- ---------------------------------------------------------------------
-- 第 2 步：分类注册表定型为四条产品线
-- ---------------------------------------------------------------------
-- FROM 派生表包一层：INSERT 的目标表不能直接出现在自身子查询里（MySQL 1093）
INSERT INTO api_project_categories (cat_key, label, sort_order, builtin)
SELECT * FROM (SELECT 'POGPOG' AS k, 'POGPOG' AS l, 30 AS s, 1 AS b) v
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT cat_key FROM api_project_categories) c WHERE c.cat_key = 'POGPOG'
);

-- label 与排序定型。注意 APP 的 label 从「APP 后端」改成「APP」——
-- 旧 label 描述的是「后端微服务」，而 APP 现在同时装客户端和后台，
-- 留着旧名会让人以为客户端放错了地方。
UPDATE api_project_categories SET label = 'APP',    sort_order = 10 WHERE cat_key = 'APP';
UPDATE api_project_categories SET label = 'PVP',    sort_order = 20 WHERE cat_key = 'PVP';
UPDATE api_project_categories SET label = 'POGPOG', sort_order = 30 WHERE cat_key = 'POGPOG';
UPDATE api_project_categories SET label = 'WEB',    sort_order = 40 WHERE cat_key = 'WEB';
UPDATE api_project_categories SET label = '其他',   sort_order = 90 WHERE cat_key = 'OTHER';


-- ---------------------------------------------------------------------
-- 第 3 步：POGPOG 归集
-- ---------------------------------------------------------------------
-- 盘点确认：global 系在库里只有 app-global-data 一个。
-- app-global-agent 不存在，global-video 尚未接入（将来新建时在项目管理里选 POGPOG）。
UPDATE api_projects SET category = 'POGPOG', kind = 'CLIENT'
WHERE code IN ('android-overseas-pogpog', 'ios-overseas-pogpog');

UPDATE api_projects SET category = 'POGPOG', kind = 'BACKEND'
WHERE code = 'app-global-data';


-- ---------------------------------------------------------------------
-- 第 4 步：平台自身项目归 OTHER
-- ---------------------------------------------------------------------
-- debug-* 是对比分析的演练项目，auth-* 是平台自己的初始化服务，
-- 都不是业务项目，不该混进 APP 树。
UPDATE api_projects SET category = 'OTHER'
WHERE code LIKE 'debug-%' OR code LIKE 'auth-%';


-- ---------------------------------------------------------------------
-- 第 5 步：其余一律归 APP
-- ---------------------------------------------------------------------
-- 阶段 01 清完之后，剩下的非 POGPOG / 非 OTHER 项目全部是完美世界电竞的
-- 后台服务与三端客户端。这里【不做规则推断】，直接把剩余的全部写实。
UPDATE api_projects
SET category = 'APP'
WHERE (category IS NULL OR TRIM(category) = '' OR category NOT IN ('APP', 'PVP', 'POGPOG', 'WEB', 'OTHER'));


-- ---------------------------------------------------------------------
-- 第 6 步：kind 定型
-- ---------------------------------------------------------------------
-- 电竞三端是客户端（阶段 01 已改名，此处确保 kind 正确）
UPDATE api_projects SET kind = 'CLIENT'
WHERE code IN ('app-esports-ios', 'app-esports-android', 'app-esports-harmonyos');

-- 其余空 kind 一律按后端（与 ApiProjectService:453 的现有默认一致）
UPDATE api_projects SET kind = 'BACKEND'
WHERE kind IS NULL OR TRIM(kind) = '';


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
-- 四条线的分布（期望：APP 19 / POGPOG 3 / OTHER 5 / PVP 0 / WEB 0）
SELECT
    c.cat_key                          AS 分类,
    c.label                            AS 展示名,
    COUNT(p.code)                      AS 项目数,
    IFNULL(SUM(p.kind = 'CLIENT'), 0)  AS 客户端,
    IFNULL(SUM(p.kind = 'BACKEND'), 0) AS 后台
FROM api_project_categories c
LEFT JOIN api_projects p ON p.category = c.cat_key
GROUP BY c.cat_key, c.label, c.sort_order
ORDER BY c.sort_order;

-- 分类表应只剩 5 条内置，无 builtin=0 的垃圾
SELECT cat_key, label, sort_order, builtin FROM api_project_categories ORDER BY sort_order;

-- 不应再有非法 category
SELECT COUNT(*) AS 非法分类数_应为0
FROM api_projects
WHERE category IS NULL OR category NOT IN ('APP', 'PVP', 'POGPOG', 'WEB', 'OTHER');

-- 电竞相关项目并列核对（确认客户端与后端 api 没写串）
SELECT code, name, category, kind FROM api_projects WHERE code LIKE 'app-esports%' ORDER BY code;

-- APP 分类全貌
SELECT code, name, kind FROM api_projects WHERE category = 'APP' ORDER BY kind DESC, code;


-- =====================================================================
-- 配套代码改动（数据迁完之后再改，顺序不能反）
-- =====================================================================
-- 先有实体值（第 1、5 步已固化），再删推断代码。反过来做，
-- 会有一段时间所有项目掉进 OTHER。
--
-- ApiProjectCatalog：
--   删 PVP_CODES / WEB_CODES 两个硬编码 Set（里面的码要么已删、要么已改名）
--   删 categoryKeyOf() 整条推断链
--   resolveCategoryKey() 简化为「只读 storedCategory，空则 OTHER」
--   projectDisplayLabel() 删掉 5 个客户端的展示名特例，改读 api_projects.name
--   ORDER 增加 POGPOG
--
-- ApiAppCatalog：
--   WMPVP_GITLAB 移除 global-agent（库里本就没有，且按新归属属 POGPOG）
--   LEGACY_ALIASES 删掉 'data-center' → 'app-data-center'（数据已归一，
--     留着会把新码再映射一次）
--
-- ApiProjectService：
--   删 CLIENT_PROJECT_CODES 与 markLegacyClientProjects()（改由 kind 列表达；
--     且里面 5 个码已在阶段 01 全部改名或删除，留着是死代码）
--   appProjectSlots() 移除 app-global-data（已归 POGPOG）
--   ★ 统一槽位渲染规则：APP 分类当前是【无条件渲染】（约 505 行），
--     其余分类是【库里存在才渲染】（约 523 行）。改成全部一致，
--     幽灵节点随之消失。
--
-- ★ ensureCategoryRegistered() 要加防线：
--   这次污染就是它造成的——见到未注册的 category 值就自动建分类，
--   于是一个写错的 label 变成了一条正式分类。应改为：
--   只接受已注册的 key，未知值记警告并落到 OTHER，不再自动建。
-- =====================================================================


-- =====================================================================
-- 回滚
-- =====================================================================
-- 本阶段全是幂等 UPDATE，可按备份里的原值改回。
-- 唯一不可逆的是第 1 步删掉的两条垃圾分类，重建即可：
--   INSERT INTO api_project_categories (cat_key, label, sort_order, builtin)
--   VALUES ('APP后端', 'APP后端', 500, 0), ('电竞iOS客户端', '电竞iOS客户端', 500, 0);
-- =====================================================================
