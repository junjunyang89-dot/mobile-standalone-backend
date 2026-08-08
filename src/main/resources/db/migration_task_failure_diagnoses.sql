-- 失败归因结论。
--
-- 一个任务允许有多条：AI 先给一条，人复核后可以再写一条纠正，两条都留着。
-- 「AI 判元素坏了、人判是真 bug」这种分歧是后面调优归因最有价值的样本，覆盖掉就没了。
-- 取「当前结论」的规则在服务层：人工优先，其次按时间取最新。
--
-- 归因结论不是执行数据，任务删了就没有保留意义，跟着级联删。

CREATE TABLE IF NOT EXISTS task_failure_diagnoses (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL COMMENT '归因对应的任务',
    execution_id VARCHAR(64) NULL COMMENT '归因对应的那次执行',
    category VARCHAR(32) NOT NULL
        COMMENT 'ELEMENT_BROKEN=元素定位失效 / FLOW_OUTDATED=流程过期 / FUNCTION_BROKEN=功能缺陷 / ENVIRONMENT=环境问题 / FLAKY=偶现 / UNKNOWN=证据不足',
    confidence INT NULL COMMENT '0-100，对结论的把握',
    failed_step VARCHAR(255) NULL COMMENT '失败步骤名，冗余便于列表直接展示',
    element_key VARCHAR(128) NULL COMMENT '失败步骤引用的元素 key，元素自愈据此找落点',
    reason TEXT NULL COMMENT '判断依据',
    suggestion TEXT NULL COMMENT '建议动作：改元素 / 改流程 / 提单 / 重跑',
    source VARCHAR(16) NOT NULL DEFAULT 'AI' COMMENT 'AI / HUMAN',
    model VARCHAR(64) NULL COMMENT '模型标识，人工结论为空',
    created_by VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task (task_id, created_at),
    INDEX idx_category (category),
    INDEX idx_element_key (element_key),
    FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务失败归因结论';

-- ============ 后续追加：AI 给出的候选定位 ============
-- 归因判成 ELEMENT_BROKEN 时，模型会顺带给出它认为正确的定位串。
-- 存下来供人一键采纳更新元素行——只给结论不给候选，人还是得自己回去找，等于没省事。
ALTER TABLE task_failure_diagnoses
    ADD COLUMN suggested_locator_type VARCHAR(32) NULL COMMENT '候选定位类型，如 xpath / id'
        AFTER element_key,
    ADD COLUMN suggested_locator_value VARCHAR(1000) NULL COMMENT '候选定位串，供一键采纳'
        AFTER suggested_locator_type,
    ADD COLUMN applied_at DATETIME NULL COMMENT '候选定位被采纳的时间，非空表示已写回元素行'
        AFTER suggested_locator_value;

-- ============ 后续追加：去重签名 + 人工复核 ============
-- 签名：同一条流程的同一步同一元素反复失败（比如每晚回归都挂在同一个地方），
-- 每次都重新调一次模型是纯浪费——结论一模一样，还会让「归因覆盖率」虚高：
-- 看着每次都有结论，其实一个问题都没解决。复用近期结论，只留一次模型调用。
-- 同一签名的记录条数天然就是「这个问题发生了多少次」，正好用来排「先修哪个最划算」。
--
-- 复核：没人看的话「人工改判率」恒为 0，区分不了「AI 判对了」和「没人看」。
-- 记下复核动作，改判率的分母才有意义。
ALTER TABLE task_failure_diagnoses
    ADD COLUMN signature VARCHAR(255) NULL COMMENT '去重签名：流程+失败步骤+元素key' AFTER element_key,
    ADD COLUMN reviewed_at DATETIME NULL COMMENT '人工复核时间；非空表示这条结论被人看过' AFTER applied_at,
    ADD COLUMN reviewed_by VARCHAR(100) NULL COMMENT '复核人' AFTER reviewed_at,
    ADD INDEX idx_signature (signature, created_at);
