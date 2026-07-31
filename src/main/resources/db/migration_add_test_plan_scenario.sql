-- 测试计划支持「按场景定时执行」：增加 scenario_id、scenario_config
-- 执行前请备份。若表已有这两列，请跳过或注释掉已存在的 ADD COLUMN。

ALTER TABLE test_plans
    ADD COLUMN scenario_id VARCHAR(64) NULL COMMENT '场景ID(如login_android)，与case_ids二选一' AFTER app_id;
ALTER TABLE test_plans
    ADD COLUMN scenario_config TEXT NULL COMMENT '场景执行时的config JSON' AFTER scenario_id;
