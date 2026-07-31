-- 自动化测试定时计划：支持批量设备、批量场景、计划类型（iOS/Android/鸿蒙/pogpog）
-- 使用前请执行本迁移：mysql -u user -p database < migration_add_scheduled_plan_batch.sql
-- 执行前请备份。若表已有这些列，请跳过或注释掉已存在的 ADD COLUMN。

ALTER TABLE test_plans
    ADD COLUMN device_ids TEXT NULL COMMENT '批量设备ID列表（JSON数组），与device_id二选一' AFTER device_id;
ALTER TABLE test_plans
    ADD COLUMN scenario_ids TEXT NULL COMMENT '批量场景ID列表（JSON数组），与scenario_id二选一' AFTER scenario_id;
ALTER TABLE test_plans
    ADD COLUMN plan_type VARCHAR(20) NULL COMMENT '计划类型：IOS, ANDROID, HARMONYOS, POGPOG' AFTER scenario_config;
