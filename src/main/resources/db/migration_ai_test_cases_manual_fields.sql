-- 手工录入用例所需的字段。
--
-- 刻意<不>加两列，理由记在这里免得以后有人"补全"：
--   实际结果        —— 用例是模板（不变），执行结果是每次执行的数据（每次都不同）。
--                      放同一行意味着第二轮覆盖第一轮，没有历史、算不出通过率。
--                      执行结果归 online_regression_steps / task_executions。
--   是否已实现自动化 —— 人手填的布尔值会立刻过期：流程停用/删除了没人回来改这一列。
--                      改由 flow_id 推导：有关联且流程仍启用＝已自动化。
--
-- flow_id 是这批字段里最值钱的一个：覆盖率、改动关联时"该跑哪些流程"、
-- 流程失败时"影响哪些用例"，三样都从它长出来。
ALTER TABLE ai_test_cases
    ADD COLUMN module          VARCHAR(100) NULL COMMENT '功能模块（一级）'      AFTER category,
    ADD COLUMN sub_module      VARCHAR(100) NULL COMMENT '子功能（二级）'        AFTER module,
    ADD COLUMN test_point      VARCHAR(255) NULL COMMENT '测试点（三级，比标题更细）' AFTER sub_module,
    ADD COLUMN expected_result TEXT         NULL COMMENT '整体期望结果，区别于步内 expected' AFTER steps_json,
    ADD COLUMN flow_id         VARCHAR(64)  NULL COMMENT '关联的 ui_flows.id，为空＝尚未自动化' AFTER expected_result,
    ADD COLUMN remark          TEXT         NULL COMMENT '备注',
    ADD INDEX idx_module (module),
    ADD INDEX idx_flow (flow_id);
