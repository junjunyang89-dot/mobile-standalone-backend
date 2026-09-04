-- =====================================================================
-- 项目归属模型 · 阶段 06：接口覆盖差集
-- =====================================================================
-- 【前置】阶段 01–05 已完成。
--
-- ---------------------------------------------------------------------
-- 为什么加这一列
-- ---------------------------------------------------------------------
-- requirement_endpoints 是两条链路的汇合点：
--   source='SYNC'    接口对比分析说「这个需求改了这些接口」
--   source='CAPTURE' 前端抓包说「这个需求实际调了这些接口」
--   source='MANUAL'  人工补的
--
-- 但 linkCaptured 的 ON DUPLICATE KEY UPDATE 刻意不改 source
-- （注释写着「抓包证明它被调用，不否定它被改过」——这个判断是对的）。
-- 代价是：一个 SYNC 接口后来被抓到，source 仍是 SYNC，于是
-- 【改了且已覆盖】和【改了但没覆盖】在数据上分不出来。
-- 而后者恰恰是最该看见的：改了却没人调，要么漏测，要么后端白改。
--
-- 加 captured_at 把这个信号单独记住，不动 source 的语义：
--   source='SYNC' AND captured_at IS NULL     → 改了但没抓到（漏测面）
--   source='SYNC' AND captured_at IS NOT NULL → 改了且已覆盖
--   source='CAPTURE'                          → 抓到但不在本次变更里（回归面）
-- =====================================================================

SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='requirement_endpoints'
             AND COLUMN_NAME='captured_at');
SET @s := IF(@c=0,
  'ALTER TABLE requirement_endpoints ADD COLUMN captured_at DATETIME NULL COMMENT ''最近一次被抓包捕获的时间；空＝从未抓到'' AFTER source',
  'SELECT ''captured_at 已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- 存量回填：source='CAPTURE' 的本来就是抓包进来的，用 linked_at 当捕获时间。
-- 不回填 SYNC/MANUAL——没有证据说明它们被抓到过，凭空填等于伪造覆盖。
UPDATE requirement_endpoints
SET captured_at = linked_at
WHERE source = 'CAPTURE' AND captured_at IS NULL;


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT source,
       COUNT(*) AS 总数,
       SUM(captured_at IS NOT NULL) AS 有抓包记录
FROM requirement_endpoints GROUP BY source;

-- 差集预览：现在就能看出每个需求的覆盖情况
SELECT requirement_no AS 需求,
       SUM(source='SYNC' AND captured_at IS NULL)     AS 改了但没抓到,
       SUM(source='SYNC' AND captured_at IS NOT NULL) AS 改了且已覆盖,
       SUM(source='CAPTURE')                          AS 抓到但不在变更里,
       SUM(source='MANUAL')                           AS 人工补的
FROM requirement_endpoints
WHERE lifecycle IS NULL OR lifecycle <> 'ARCHIVED'
GROUP BY requirement_no ORDER BY requirement_no;


-- =====================================================================
-- 回滚
-- =====================================================================
-- ALTER TABLE requirement_endpoints DROP COLUMN captured_at;
-- =====================================================================
