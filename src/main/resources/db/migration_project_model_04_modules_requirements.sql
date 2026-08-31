-- =====================================================================
-- 项目归属模型 · 阶段 04：功能模块 + 需求登记 + 版本归属
-- =====================================================================
-- 【前置】阶段 01/02/03 已完成。
--
-- ---------------------------------------------------------------------
-- 为什么要做这三张表（盘点依据，2026-08-31 实测）
-- ---------------------------------------------------------------------
-- 1) 功能模块已严重漂移。moduleTag / module 是自由文本，实测值：
--      ui_elements:  数据壳(64) / inspector(61) / DOTA2 个人数据壳(35)
--                    异锁 / Deadlock 数据壳(33) / 异锁生涯数据 / 更多数据(31)
--      ai_test_cases: 登录注册(47) / DEADLOCK(13) / 安装(10) / 卸载(7)
--    三个毛病一眼可见：层级压进一个字段（数据壳 vs DOTA2 个人数据壳）、
--    斜杠拼接的复合名、混入非业务概念（inspector 是工具名，安装/卸载是操作）。
--    和之前修掉的分类污染同源，只是规模更大。
--
-- 2) 需求单没有实体。requirement_no 只是散在 api_test_collections /
--    requirement_endpoints / requirement_bugs 里的字符串（APP-8999、APP-9059），
--    没有任何地方能回答「这个需求覆盖了哪些模块、属于哪个版本、测到什么程度」。
--
-- 3) 版本注册表已存在但零引用。release_version_archives 有 3 行
--    （4.1.6 / 9.3 国内客户端 / 国内客户端），而 api_test_collections.app_version
--    9 行全空。且它的 product_line 用「国内客户端 / 海外客户端」，
--    与 APP/PVP/POGPOG/WEB 对不上——那是平台里的第四套分类法。
--
-- ---------------------------------------------------------------------
-- 设计取舍
-- ---------------------------------------------------------------------
-- 模块挂在【分类（产品线）】而不是每个项目：实测模块值全是客户端功能
-- （数据壳/赛事壳/我的个人页），app-seckill 这种后端项目根本没有「数据壳」。
-- 挂到项目下的话，app-esports-ios 与 app-esports-android 会各维护一份
-- 完全重复的模块树——两端功能本来就一样。
--
-- git 分支与 JIRA 单号是【外部事实】，平台只引用不做主数据：
-- 分支从 git 读（现有 /api-changes/branches 已经这么做），
-- 单号手工登记到 requirements，但不与 JIRA 同步。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 第 1 步：功能模块树（挂在分类下，项目共用）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS project_modules (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    /* 归属产品线，对应 api_project_categories.cat_key */
    category    VARCHAR(64)  NOT NULL,
    /* 自引用，支持层级：数据壳 → DOTA2 数据壳。NULL 表示顶层 */
    parent_id   VARCHAR(64)  NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    sort_order  INT          DEFAULT 100,
    enabled     TINYINT(1)   DEFAULT 1,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_pm_category (category, parent_id, sort_order),
    CONSTRAINT fk_pm_category FOREIGN KEY (category)  REFERENCES api_project_categories(cat_key) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_pm_parent   FOREIGN KEY (parent_id) REFERENCES project_modules(id)             ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='功能模块树，按产品线维护，项目共用';
-- 同父下不重名的约束【放在 Service 层】而不是唯一索引：
-- MySQL 唯一索引里 NULL 互不相等，(APP, NULL, '数据壳') 能重复插入两次，
-- 顶层模块恰恰全是 parent_id IS NULL，索引挡不住最需要挡的那一类。


-- ---------------------------------------------------------------------
-- 第 2 步：需求登记
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS requirements (
    /* JIRA 单号，如 APP-8999。外部系统的事实，这里只登记不同步 */
    requirement_no VARCHAR(64)   NOT NULL PRIMARY KEY,
    title          VARCHAR(512)  NULL,
    /* 主要归属项目；一个需求可能横跨多个项目，这里记主责的那个 */
    project_code   VARCHAR(64)   NULL,
    /* 归属版本，对应 release_version_archives.id */
    version_id     VARCHAR(64)   NULL,
    /* PLANNING 待测 / TESTING 测试中 / DONE 已完成 / CANCELLED 已取消 */
    status         VARCHAR(16)   NOT NULL DEFAULT 'PLANNING',
    owner          VARCHAR(64)   NULL COMMENT '测试负责人',
    note           VARCHAR(1024) NULL,
    created_at     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_req_project (project_code),
    KEY idx_req_version (version_id),
    CONSTRAINT fk_req_project FOREIGN KEY (project_code) REFERENCES api_projects(code)            ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT fk_req_version FOREIGN KEY (version_id)   REFERENCES release_version_archives(id)  ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='需求登记；单号来自 JIRA，此处手工录入';
-- project_code / version_id 用 SET NULL 而不是 RESTRICT：
-- 需求是历史记录，不该因为项目或版本被删就连带消失，丢掉归属即可。


-- ---------------------------------------------------------------------
-- 第 3 步：需求 ↔ 模块（多对多）
-- ---------------------------------------------------------------------
-- 一个需求经常横跨好几个模块，一对多装不下。
CREATE TABLE IF NOT EXISTS requirement_modules (
    requirement_no VARCHAR(64) NOT NULL,
    module_id      VARCHAR(64) NOT NULL,
    created_at     DATETIME    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (requirement_no, module_id),
    KEY idx_rm_module (module_id),
    CONSTRAINT fk_rm_req    FOREIGN KEY (requirement_no) REFERENCES requirements(requirement_no) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rm_module FOREIGN KEY (module_id)      REFERENCES project_modules(id)          ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='需求覆盖的功能模块';


-- ---------------------------------------------------------------------
-- 第 4 步：版本归属统一到分类 key
-- ---------------------------------------------------------------------
-- product_line 现在是「国内客户端 / 海外客户端」，是平台里的第四套分类法。
-- 换成引用 category，与项目树同一套取值。
SET @has_cat := (SELECT COUNT(1) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'release_version_archives'
                   AND COLUMN_NAME = 'category');
SET @sql := IF(@has_cat = 0,
    'ALTER TABLE release_version_archives ADD COLUMN category VARCHAR(64) NULL COMMENT ''归属产品线，对应 api_project_categories.cat_key'' AFTER product_line',
    'SELECT ''category 列已存在，跳过''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 按实测的 product_line 取值迁移。国内客户端＝电竞＝APP，海外＝POGPOG。
UPDATE release_version_archives SET category = 'APP'    WHERE product_line = '国内客户端' AND (category IS NULL OR category = '');
UPDATE release_version_archives SET category = 'POGPOG' WHERE product_line = '海外客户端' AND (category IS NULL OR category = '');
UPDATE release_version_archives SET category = 'OTHER'  WHERE category IS NULL OR category = '';

-- 迁完再加外键，否则残留的旧值会让 DDL 直接失败。
-- 同样做幂等守卫：ADD CONSTRAINT 重复执行会因约束重名而失败，
-- 而这个脚本是要能安全重跑的。
SET @has_fk := (SELECT COUNT(1) FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'release_version_archives'
                  AND CONSTRAINT_NAME = 'fk_rva_category');
SET @sql := IF(@has_fk = 0,
    'ALTER TABLE release_version_archives ADD CONSTRAINT fk_rva_category FOREIGN KEY (category) REFERENCES api_project_categories(cat_key) ON DELETE RESTRICT ON UPDATE CASCADE',
    'SELECT ''fk_rva_category 已存在，跳过''');
PREPARE s2 FROM @sql; EXECUTE s2; DEALLOCATE PREPARE s2;
-- product_line 暂时保留：它是人写的对外叫法，与 category 不是一回事，
-- 且发版文档还在读它。等确认无引用后再单独清理。


-- ---------------------------------------------------------------------
-- 第 5 步：把散落的需求单号收进登记表
-- ---------------------------------------------------------------------
-- 实测散在两处：api_test_collections（APP-8999）、requirement_endpoints
-- （APP-8999、APP-9059）。先建档，标题和版本留空等人补。
INSERT IGNORE INTO requirements (requirement_no, title, status, note)
SELECT DISTINCT requirement_no, NULL, 'TESTING', '由历史数据自动登记，请补充标题与版本'
FROM (
    SELECT requirement_no FROM api_test_collections  WHERE requirement_no IS NOT NULL AND TRIM(requirement_no) <> ''
    UNION
    SELECT requirement_no FROM requirement_endpoints WHERE requirement_no IS NOT NULL AND TRIM(requirement_no) <> ''
    UNION
    SELECT requirement_no FROM requirement_bugs      WHERE requirement_no IS NOT NULL AND TRIM(requirement_no) <> ''
) s;


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT
    (SELECT COUNT(*) FROM project_modules)      AS 模块数_初始为0,
    (SELECT COUNT(*) FROM requirements)         AS 需求数,
    (SELECT COUNT(*) FROM requirement_modules)  AS 需求模块关联数_初始为0;

SELECT requirement_no, status, note FROM requirements ORDER BY requirement_no;

SELECT app_version, release_label, product_line, category FROM release_version_archives ORDER BY release_date DESC;

-- project_modules 2（category / parent）+ requirements 2（project / version）
-- + requirement_modules 2（req / module）+ release_version_archives 1（category）
SELECT COUNT(*) AS 新增外键数_应为7
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('project_modules', 'requirements', 'requirement_modules', 'release_version_archives')
  AND REFERENCED_TABLE_NAME IS NOT NULL;


-- =====================================================================
-- 后续（本脚本不做）
-- =====================================================================
-- 1) 模块树初始内容【不自动生成】。现有 moduleTag 的值层级混乱、含斜杠复合名
--    与非业务概念，机器猜不准该拆成什么样。应在页面上手工建一版干净的树，
--    再把老的 moduleTag 逐个映射过去。自动导入只会把脏数据原样搬进新表。
--
-- 2) ui_elements.module_tag / ui_pages.module_tag / ai_test_cases.module
--    暂不加外键——存量值还没映射完，加了会直接写不进去。
--    等模块树建好、映射完成，再单独一个脚本收口。
--
-- 3) api_test_collections.app_version 目前是自由文本且全空，
--    应改为引用 release_version_archives.id。等页面改成下拉选择后再迁。
-- =====================================================================


-- =====================================================================
-- 回滚
-- =====================================================================
-- DROP TABLE IF EXISTS requirement_modules;
-- DROP TABLE IF EXISTS requirements;
-- DROP TABLE IF EXISTS project_modules;
-- ALTER TABLE release_version_archives DROP FOREIGN KEY fk_rva_category;
-- ALTER TABLE release_version_archives DROP COLUMN category;
-- 三张表都是新增，删掉即回到阶段 03 结束时的状态。
-- =====================================================================
