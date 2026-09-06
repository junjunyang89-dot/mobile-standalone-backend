-- =====================================================================
-- 接口响应快照（换皮回归基准）
-- =====================================================================
-- 活动页每期换皮、前端全改，但接口返回的结构基本不变。上线前把响应存成基线，
-- 换皮后再抓一次对比结构——后端少返字段、奖品从 8 个变 7 个、字段类型漂移，
-- 都能一眼看出来，而且完全不碰页面、不依赖任何选择器，换皮打不穿。
--
-- 只存【结构指纹 + 一份样本响应】，不存全部流量：指纹拿来 diff，样本供人核对。
-- 响应体本身仍在抓包目录的磁盘文件里，这里不重复存几十 MB。
-- =====================================================================

CREATE TABLE IF NOT EXISTS response_snapshots (
    id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL COMMENT '如「国服战令 9.3 基线」',
    requirement_no VARCHAR(64)  NULL,
    project        VARCHAR(64)  NULL,
    /* 这份基线是从哪一场抓包提升上来的，便于溯源 */
    source_session VARCHAR(64)  NULL,
    entry_count    INT          DEFAULT 0,
    note           VARCHAR(512) NULL,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rs_req (requirement_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='接口响应基线';

CREATE TABLE IF NOT EXISTS response_snapshot_entries (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    snapshot_id  VARCHAR(64)  NOT NULL,
    http_method  VARCHAR(16)  NOT NULL,
    /* 已剥掉 a/r/t/s 等签名参数的稳定路径——它们每次都变，留着会让同一个接口
       在基线里出现无数份，对不上号 */
    path         VARCHAR(512) NOT NULL,
    status       INT          NULL,
    /* ResponseShape 规约出的结构指纹，diff 就比它 */
    shape        MEDIUMTEXT   NULL,
    /* 一份样本响应（截断），只给人核对用，不参与结构比对 */
    sample_body  MEDIUMTEXT   NULL,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rse_snapshot (snapshot_id),
    KEY idx_rse_ep (snapshot_id, http_method, path),
    CONSTRAINT fk_rse_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES response_snapshots(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='基线里每个接口的结构指纹与样本';


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT
    (SELECT COUNT(*) FROM response_snapshots)         AS 基线数_初始为0,
    (SELECT COUNT(*) FROM response_snapshot_entries)  AS 条目数_初始为0;


-- =====================================================================
-- 回滚
-- =====================================================================
-- DROP TABLE IF EXISTS response_snapshot_entries;
-- DROP TABLE IF EXISTS response_snapshots;
-- =====================================================================
