-- =====================================================================
-- 项目归属模型 · 阶段 03：ui_apps ↔ api_projects 映射
-- =====================================================================
-- 目的：补上缺失的那一层关联，让 UI 自动化资产（流程/页面/元素）能沿链路
--       定位到项目，从而与接口测试挂进同一棵树。这是阶段 04 统一树的硬前提。
--
-- 【前置】阶段 01、02 已完成（27 个项目 / 11 个外键 / 分类已定型）。
--
-- ---------------------------------------------------------------------
-- 盘点依据（2026-08-30 实测，不是推测）
-- ---------------------------------------------------------------------
-- ui_apps 共 4 行，其中只有 DOMESTIC_ESPORTS 有资产：
--   DOMESTIC_ESPORTS          完美世界电竞（国内）  19 流程 / 14 页面 / 1523 元素
--   DOMESTIC_ESPORTS_HARMONY  完美世界电竞（鸿蒙）  0 / 0 / 0
--   OVERSEAS_POGPOG           PogPog（海外）        0 / 0 / 0
--   HARMONYOS_ESPORTS         鸿蒙完美世界电竞      0 / 0 / 0   ← 与上面鸿蒙那条重复
--
-- ui_elements 带 platform 列（DOMESTIC_ESPORTS: ANDROID 1433 / IOS 90），
-- 所以映射按端拆得开——这正是本表要有 platform 列的原因：
-- 一个 ui_app 同时覆盖 Android 和 iOS，而项目侧是按端拆开的
-- （app-esports-android / app-esports-ios）。没有 platform，
-- 1523 个元素会同时算进两个项目，看着像翻倍。
--
-- ⚠ tasks / test_plans / test_reports 的 app_id 【不是】 ui_apps.id
--   （216 条 task 中 0 条匹配），它们指向 apps 表＝设备上已安装的应用实例。
--   那是设备维度，与本映射无关，不要混为一谈。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 第 1 步：建映射表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_project_links (
    app_id       VARCHAR(64)  NOT NULL COMMENT 'ui_apps.id',
    project_code VARCHAR(64)  NOT NULL COMMENT 'api_projects.code',
    /*
     * 该关联针对哪个端。ANDROID / IOS / HARMONY；NULL 表示不分端。
     * 一个 ui_app 覆盖多端、而项目按端拆开，靠这一列把资产归到对的项目上。
     */
    platform     VARCHAR(16)  NULL,
    /*
     * OWNS   = 这个项目就是该 App 的客户端本体（app-esports-ios 之于 电竞国内）
     * SERVES = 这个项目是为该 App 提供服务的后端
     * 目前只种 OWNS：后端与 App 的从属关系已由 category 表达（APP 分类下的后端
     * 天然服务于电竞 App），再种一遍 SERVES 是冗余。留着这个取值是为了将来
     * 出现「某后端只服务海外」这类需要显式表达的情况。
     */
    relation     VARCHAR(16)  NOT NULL DEFAULT 'OWNS',
    note         VARCHAR(255) NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (app_id, project_code),
    KEY idx_apl_project (project_code),
    CONSTRAINT fk_apl_app     FOREIGN KEY (app_id)       REFERENCES ui_apps(id)      ON DELETE CASCADE  ON UPDATE CASCADE,
    CONSTRAINT fk_apl_project FOREIGN KEY (project_code) REFERENCES api_projects(code) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客户端 App 与后端/客户端项目的关联';
-- 说明：app_id 用 CASCADE（App 删了，关联跟着没意义）；
--       project_code 用 RESTRICT（项目还被 App 关联着就不该删）。


-- ---------------------------------------------------------------------
-- 第 2 步：按实测数据种关联
-- ---------------------------------------------------------------------
-- 只种「客户端本体」关联，且只种 ui_apps 与 api_projects 两边都真实存在的行。
INSERT IGNORE INTO app_project_links (app_id, project_code, platform, relation, note)
SELECT s.app_id, s.project_code, s.platform, 'OWNS', s.note
FROM (
    SELECT 'DOMESTIC_ESPORTS'         AS app_id, 'app-esports-android'     AS project_code, 'ANDROID' AS platform, '电竞国内 · Android 端'  AS note UNION ALL
    SELECT 'DOMESTIC_ESPORTS',              'app-esports-ios',                  'IOS',                '电竞国内 · iOS 端'                UNION ALL
    SELECT 'DOMESTIC_ESPORTS_HARMONY',      'app-esports-harmonyos',            'HARMONY',            '电竞鸿蒙端'                       UNION ALL
    SELECT 'OVERSEAS_POGPOG',               'android-overseas-pogpog',          'ANDROID',            'PogPog 海外 · Android 端'         UNION ALL
    SELECT 'OVERSEAS_POGPOG',               'ios-overseas-pogpog',              'IOS',                'PogPog 海外 · iOS 端'
) s
JOIN ui_apps      a ON a.id   = s.app_id
JOIN api_projects p ON p.code = s.project_code;


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
-- 关联全貌（期望 5 行）
SELECT l.app_id, a.name_zh AS app名称, l.project_code, p.name AS 项目名, l.platform, l.relation
FROM app_project_links l
JOIN ui_apps a      ON a.id   = l.app_id
JOIN api_projects p ON p.code = l.project_code
ORDER BY l.app_id, l.platform;

-- 沿映射统计每个项目的 UI 资产（platform 为空的关联不按端过滤）
SELECT
    l.project_code                       AS 项目,
    l.platform                           AS 端,
    (SELECT COUNT(*) FROM ui_elements e
      WHERE e.app_id = l.app_id
        AND (l.platform IS NULL OR UPPER(IFNULL(e.platform,'')) = l.platform)) AS 元素数,
    (SELECT COUNT(*) FROM ui_flows f WHERE f.app_id = l.app_id) AS 流程数_不分端,
    (SELECT COUNT(*) FROM ui_pages g WHERE g.app_id = l.app_id) AS 页面数_不分端
FROM app_project_links l
ORDER BY 元素数 DESC;

-- 还没挂上任何项目的 App（需人工处理）
SELECT a.id, a.name_zh, '未关联任何项目' AS 状态
FROM ui_apps a
LEFT JOIN app_project_links l ON l.app_id = a.id
WHERE l.app_id IS NULL;


-- =====================================================================
-- 待你确认
-- =====================================================================
-- HARMONYOS_ESPORTS（鸿蒙完美世界电竞）与 DOMESTIC_ESPORTS_HARMONY
-- （完美世界电竞（鸿蒙））是同一个东西的两条记录，且【两条都是 0 资产】。
-- 本脚本只给 DOMESTIC_ESPORTS_HARMONY 建了关联，HARMONYOS_ESPORTS 悬空。
-- 确认后删掉多余那条：
--   DELETE FROM ui_apps WHERE id = 'HARMONYOS_ESPORTS';
-- （它零引用，删了不丢数据；关联表的外键是 CASCADE，不会挡住）
--
-- 另：ui_flows 有 4 行 app_id 为 NULL，这些流程不属于任何 App，
-- 因而也落不到任何项目上。要么补 app_id，要么确认它们是通用组件。
-- =====================================================================


-- =====================================================================
-- 回滚
-- =====================================================================
-- DROP TABLE IF EXISTS app_project_links;
-- 该表是新增的，删掉即回到阶段 02 结束时的状态，不影响任何既有数据。
-- =====================================================================
