-- 流程多步连拍后 execution_log JSON 易超过 TEXT(64KB)，改为 MEDIUMTEXT
-- 可重复执行

ALTER TABLE task_executions
  MODIFY COLUMN execution_log MEDIUMTEXT NULL COMMENT '执行步骤日志 JSON 数组';
