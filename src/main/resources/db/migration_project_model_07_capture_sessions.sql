-- =====================================================================
-- 项目归属模型 · 阶段 07：抓包会话持久化
-- =====================================================================
-- 【前置】阶段 01–06 已完成。
--
-- ---------------------------------------------------------------------
-- 为什么
-- ---------------------------------------------------------------------
-- 抓包现在落在 Files.createTempFile("capture-", ".jsonl")，会话只活在内存的
-- ConcurrentHashMap 里，而且下一场开始时 purgeLastBodies() 会把上一场的响应体删掉。
-- 也就是说：后端一重启、或者再抓一次，上一场就没了。
--
-- 这对「抓完当场解析出接口、关联到需求」是够用的，用完即弃本来就是设计意图。
-- 但有两件事需要它留下来：
--   ① 性能测试要基于历史流量算请求量配比，同一份抓包还要反复调参重新生成 JMX
--   ② 日常也常有「昨天那次抓的接口再看一眼」的需求，重启就丢是实打实的损失
--
-- ---------------------------------------------------------------------
-- 刻意不做的
-- ---------------------------------------------------------------------
-- 响应体【仍然不进库】。原代码的判断是对的：几十 MB 的响应存进库，换来的只是
-- 把库撑大，而「这个需求走了哪些接口」根本不需要它。这里只把【会话元数据】入库，
-- 流水与响应体仍旧在磁盘上，只是从临时目录挪到稳定目录，并且不再被下一场删掉。
-- 磁盘增长由保留策略兜（见 CaptureRetentionService）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS capture_sessions (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    device_id      VARCHAR(128) NULL,
    platform       VARCHAR(16)  NULL COMMENT 'ANDROID / IOS',
    /* 抓这一场是为了哪个需求；关联到 requirements，但不加外键——
       抓包常常先于需求登记发生，加了外键会让「先抓再补单号」这条路走不通 */
    requirement_no VARCHAR(64)  NULL,
    project        VARCHAR(64)  NULL,
    hosts          VARCHAR(1024) NULL COMMENT '本场的域名白名单',
    /* 流水文件所在目录。流水与响应体都在磁盘上，库里只记位置 */
    store_dir      VARCHAR(512) NULL,
    flow_count     INT          DEFAULT 0,
    host_count     INT          DEFAULT 0,
    /* RUNNING / FINISHED / ABORTED。重启后残留的 RUNNING 由启动时的对账改成 ABORTED */
    status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    started_at     DATETIME     NULL,
    stopped_at     DATETIME     NULL,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_cs_req (requirement_no),
    KEY idx_cs_started (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='抓包会话；流水与响应体在磁盘上，这里只存元数据';


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS 会话数_初始为0 FROM capture_sessions;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'capture_sessions'
ORDER BY ORDINAL_POSITION;


-- =====================================================================
-- 回滚
-- =====================================================================
-- DROP TABLE IF EXISTS capture_sessions;
-- 新增表，删掉即回到阶段 06 结束时的状态。磁盘上的抓包文件需要手工清理。
-- =====================================================================
