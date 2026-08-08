-- 流程的「环境」维度，与 catalog（轨道）正交。
--
-- 在这之前只有 catalog 一个字段，曾经被迫同时表达两件事：
--   「哪个入口用」（回归 / 自动化编排 / 性能）和「哪个环境的用例」（线上 / 测试）。
-- 结果是 SHARED 一改就串环境、AUTOMATION 一占就没地方放线上自动化用例。拆开之后：
--   catalog      = 轨道：AUTOMATION / ONLINE / SHARED / PERFORMANCE
--   environment  = 环境：PROD / TEST
--
-- 回填规则刻意复现迁移前的行为，跑完这段等于什么都没变：
--   ONLINE / SHARED / PERFORMANCE -> PROD（这批本来就是线上回归与性能用例）
--   AUTOMATION                    -> TEST（这批本来就是测试环境发版前回归在用）
-- 回填后请自行调整：线上自动化用例应改成 catalog=AUTOMATION + environment=PROD。

ALTER TABLE ui_flows
    ADD COLUMN environment VARCHAR(16) NOT NULL DEFAULT 'PROD'
        COMMENT '环境：PROD / TEST，与 catalog 正交' AFTER catalog,
    ADD INDEX idx_environment (environment);

UPDATE ui_flows SET environment = 'TEST' WHERE catalog = 'AUTOMATION';
UPDATE ui_flows SET environment = 'PROD' WHERE catalog <> 'AUTOMATION';
