-- =====================================================================
-- 项目归属模型 · 阶段 05：上线收口 + 变量项目作用域
-- =====================================================================
-- 【前置】阶段 01–04 已完成。
--
-- ---------------------------------------------------------------------
-- 为什么改（盘点依据，实测）
-- ---------------------------------------------------------------------
-- 1) 「本单上线」只收了一半的口。releaseRequirement 归档了测试集合与涉及接口，
--    但不碰两样东西：
--      - ai_test_cases（黑盒功能用例）：98 条 TESTING 全部带需求号，上线后仍停在 TESTING
--      - requirements.status：需求登记表建好后没接上，上线了还是 TESTING
--    结果是一个动作要在三个地方各点一次，漏一处链路就断了。
--
-- 2) 分支合并 master 这个事实没有落点。分支现在记在集合上，
--    但一个需求对应一个分支、一个需求可能有多个集合——归属层级错了。
--
-- 3) env_variables 只有 environment 维度，没有 project。目前 4 个变量
--    （手机号、验证码）全局共享没问题，但两个项目各需要一个不同的 token 时，
--    只能靠变量名前缀去躲——那是分类污染的老路。
-- =====================================================================


-- ---------------------------------------------------------------------
-- 第 1 步：需求登记补分支与合并时间
-- ---------------------------------------------------------------------
SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='requirements' AND COLUMN_NAME='source_branch');
SET @s := IF(@c=0,
  'ALTER TABLE requirements ADD COLUMN source_branch VARCHAR(255) NULL COMMENT ''开发分支，如 fch/20260804/APP-8928'' AFTER version_id',
  'SELECT ''source_branch 已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='requirements' AND COLUMN_NAME='merged_at');
SET @s := IF(@c=0,
  'ALTER TABLE requirements ADD COLUMN merged_at DATETIME NULL COMMENT ''分支合并到 master 的时间；标记上线时写入'' AFTER source_branch',
  'SELECT ''merged_at 已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;


-- ---------------------------------------------------------------------
-- 第 2 步：环境变量加项目作用域
-- ---------------------------------------------------------------------
-- 主键必须一起改：原主键是 (environment, name)，不带 project 的话，
-- 全局 token 和某项目专属 token 会主键冲突，等于这个功能加了也用不了。
--
-- 用 NOT NULL DEFAULT '' 而不是可空：MySQL 主键列不允许 NULL，
-- 所以拿空串当「全局」的哨兵值。代价是没法给它加外键
-- （'' 在 api_projects 里不存在），项目存在性改在 Service 层校验。
SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='env_variables' AND COLUMN_NAME='project');
SET @s := IF(@c=0,
  'ALTER TABLE env_variables
     ADD COLUMN project VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''归属项目；空串=该环境下全局'' AFTER environment,
     DROP PRIMARY KEY,
     ADD PRIMARY KEY (environment, project, name)',
  'SELECT ''project 列已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;
-- 存量 4 条自动落到 project=''（全局），行为不变。


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE()
  AND ((TABLE_NAME='requirements' AND COLUMN_NAME IN ('source_branch','merged_at'))
    OR (TABLE_NAME='env_variables' AND COLUMN_NAME='project'))
ORDER BY TABLE_NAME, COLUMN_NAME;

-- 新主键应为 environment,project,name
SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS 主键列
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='env_variables' AND INDEX_NAME='PRIMARY';

-- 存量变量应全部落在全局作用域
SELECT IFNULL(NULLIF(project,''),'(全局)') AS 作用域, COUNT(*) n FROM env_variables GROUP BY project;


-- =====================================================================
-- 回滚
-- =====================================================================
-- ALTER TABLE requirements DROP COLUMN merged_at, DROP COLUMN source_branch;
-- ALTER TABLE env_variables DROP PRIMARY KEY, DROP COLUMN project,
--   ADD PRIMARY KEY (environment, name);
--   （回滚前须确认没有同名跨项目变量，否则恢复旧主键会因重复而失败）
-- =====================================================================
