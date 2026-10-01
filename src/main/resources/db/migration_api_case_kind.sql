-- 接口用例：加「这条用例为什么存在」(case_kind) 和来源分支 (source_branch)
-- mysql ... < migration_api_case_kind.sql
--
-- 背景：分支用例（为某次提测写的）和回归用例（长期守已有行为）寿命完全不同，
-- 挂了的含义也不同，但现在混在一张表里无法区分。详见 ApiCaseKind 的类注释。

-- 为什么是 NOT NULL DEFAULT 而不是允许 NULL：
--
-- 允许 NULL 的话，「定期回归要跑哪些」这个查询会写成 case_kind <> 'BRANCH'，
-- 而 SQL 里 NULL <> 'BRANCH' 的结果是 NULL（不是 TRUE），于是库里
-- 全部历史用例会在加这个字段那天集体从回归里消失 —— 而少跑用例是静默的，
-- 没人会发现覆盖掉了，通过率还更好看。
--
-- 把这个坑在 schema 层消掉，比指望每个写 SQL 的人都记得加 IS NULL 可靠。
-- 'UNCLASSIFIED' 也不是编出来的值：它就是「没人分过类」，对老数据是实话。
ALTER TABLE api_test_cases
    ADD COLUMN case_kind VARCHAR(16) NOT NULL DEFAULT 'UNCLASSIFIED'
        COMMENT 'BRANCH=为某次提测写的 / REGRESSION=长期守已有行为 / UNCLASSIFIED=加字段前就有的',
    ADD COLUMN source_branch VARCHAR(191) DEFAULT NULL
        COMMENT '为哪个提测分支写的，如 mike/20260901/APP-9083；升级成回归用例后不要清空，它是来历';

-- 待分诊队列的查询：case_kind='BRANCH' 且 source_branch 已合进 master
CREATE INDEX idx_atc_case_kind ON api_test_cases (case_kind);
CREATE INDEX idx_atc_source_branch ON api_test_cases (source_branch);
