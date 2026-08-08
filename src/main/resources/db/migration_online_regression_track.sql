-- 批次的「轨道」：REGRESSION（回归）/ AUTOMATION（自动化编排批量执行）。
-- 与 environment 正交：回归和自动化各自都有线上、测试两个环境。
-- 存量记录留 NULL，代码里按 REGRESSION 解读，所以不需要回填。
ALTER TABLE online_regression_runs
    ADD COLUMN track VARCHAR(16) NULL COMMENT 'REGRESSION/AUTOMATION，空按 REGRESSION' AFTER environment;
