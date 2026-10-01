-- 运行结果逐条记录：加「这条用例当时是什么种类」
-- mysql ... < migration_run_case_kind.sql
--
-- 用途：通过率按桶分开算。分支用例挂了＝这次改动有问题（找开发），
-- 回归用例挂了＝回归了（拉警报）。混进一个分母后那个百分数就不再能支撑任何决定。

-- 为什么在这张表快照，而不是查询时 join api_test_cases：
--
-- 一条分支用例在提测结束后会被分诊 —— 退役，或者升级成回归用例。
-- 如果分桶靠 join 现在的 case_kind，那次升级会把【上周那次运行】的分桶一起改掉：
-- 当时它确实是作为分支用例跑的，报表却会显示它在回归桶里。
-- 历史数据被静默改写，而且没有任何地方会报错。
--
-- 这张表本来就是这个模式 —— name / method / path / url 都是快照，
-- 正是因为用例可能被改、被删。

-- 为什么这里允许 NULL，而 api_test_cases.case_kind 是 NOT NULL：
--
-- 那张表的每一行都【确实有】一个种类（哪怕是「没人分过类」＝UNCLASSIFIED）；
-- 这张表的历史行【确实不知道】当时是什么 —— 加字段之前就跑完了。
-- 填一个值进去等于编造。空值在这里的含义是「没记录」，不是「未分类」，
-- 折叠分桶时单独成一组（见 RunPassRateBuckets.UNRECORDED）。
--
-- NULL 在这里也不会踩 <> 的坑：分桶走 GROUP BY，NULL 自己成一组，看得见。
ALTER TABLE api_test_run_cases
    ADD COLUMN case_kind VARCHAR(16) DEFAULT NULL
        COMMENT '运行时这条用例的种类；空＝没记录（历史运行，或用例查不到的 BLOCKED）';

-- 分桶查询是 WHERE run_id = ? GROUP BY case_kind
CREATE INDEX idx_atrc_run_kind ON api_test_run_cases (run_id, case_kind);
