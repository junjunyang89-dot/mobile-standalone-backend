-- 性能测试长时间运行产生的 result_data JSON 可能超过 TEXT 上限（约 64KB），导致写入失败、任务被误标为失败。
-- 将 result_data 改为 MEDIUMTEXT（最大 16MB），执行本迁移后无需改应用配置。
ALTER TABLE task_executions MODIFY COLUMN result_data MEDIUMTEXT NULL COMMENT '结构化结果数据(JSON)，如兼容性/性能测试结果';
