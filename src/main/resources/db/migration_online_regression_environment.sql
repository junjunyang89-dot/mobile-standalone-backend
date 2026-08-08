-- 回归批次记录跑的是哪个环境（PROD / TEST）。
-- 存量记录留 NULL，代码里一律按 PROD 解读，所以不需要回填。
ALTER TABLE online_regression_runs
    ADD COLUMN environment VARCHAR(16) NULL COMMENT 'PROD/TEST，空按 PROD' AFTER enable_overseas_proxy;
