-- 平台去 POG：POG_ANDROID / POG_IOS / POGPOG 把「操作系统」和「产品线」混在了一个维度里。
-- 海外应用由 app_id（OVERSEAS_POGPOG）区分即可，平台只表达操作系统。
--
-- 代码侧已做兼容：执行时按 ANDROID / IOS 查不到会自动回退查 POG_*，所以这份迁移可以随时跑，
-- 不需要停机窗口。跑完之后回退路径不再命中，日志里也不会再出现「元素仍存在历史平台名下」。
--
-- 建议先跑「迁移前核对」看看影响面，再执行 UPDATE。

-- ============ 迁移前核对：看看各表有多少行受影响 ============
SELECT 'ui_elements' AS table_name, platform, COUNT(*) AS rows_affected
FROM ui_elements WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG') GROUP BY platform
UNION ALL
SELECT 'ui_flows', platform, COUNT(*)
FROM ui_flows WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG') GROUP BY platform
UNION ALL
SELECT 'tasks', platform, COUNT(*)
FROM tasks WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG') GROUP BY platform
UNION ALL
SELECT 'devices', platform, COUNT(*)
FROM devices WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG') GROUP BY platform
UNION ALL
SELECT 'applications', platform, COUNT(*)
FROM applications WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG') GROUP BY platform;

-- ============ 冲突检查：合并平台后是否会出现重复元素 ============
-- 元素查找键是 (app_id, platform, name_en)，app_id 已区分国内/海外，正常不该有冲突。
-- 有输出说明同一 app 下同名元素在两个平台都存在，需要人工确认保留哪条。
SELECT app_id, name_en, COUNT(*) AS dup_count,
       GROUP_CONCAT(CONCAT(platform, ':', id) SEPARATOR ' | ') AS rows_detail
FROM ui_elements
WHERE platform IN ('ANDROID', 'POG_ANDROID', 'POGPOG')
GROUP BY app_id, name_en
HAVING COUNT(*) > 1;

SELECT app_id, name_en, COUNT(*) AS dup_count,
       GROUP_CONCAT(CONCAT(platform, ':', id) SEPARATOR ' | ') AS rows_detail
FROM ui_elements
WHERE platform IN ('IOS', 'POG_IOS')
GROUP BY app_id, name_en
HAVING COUNT(*) > 1;

-- ============ 正式迁移 ============
-- 上面两个冲突检查都没输出，再执行以下 UPDATE。

UPDATE ui_elements SET platform = 'ANDROID', updated_at = NOW()
WHERE platform IN ('POG_ANDROID', 'POGPOG');
UPDATE ui_elements SET platform = 'IOS', updated_at = NOW()
WHERE platform = 'POG_IOS';

UPDATE ui_flows SET platform = 'ANDROID', updated_at = NOW()
WHERE platform IN ('POG_ANDROID', 'POGPOG');
UPDATE ui_flows SET platform = 'IOS', updated_at = NOW()
WHERE platform = 'POG_IOS';

UPDATE tasks SET platform = 'ANDROID', updated_at = NOW()
WHERE platform IN ('POG_ANDROID', 'POGPOG');
UPDATE tasks SET platform = 'IOS', updated_at = NOW()
WHERE platform = 'POG_IOS';

UPDATE devices SET platform = 'ANDROID'
WHERE platform IN ('POG_ANDROID', 'POGPOG');
UPDATE devices SET platform = 'IOS'
WHERE platform = 'POG_IOS';

UPDATE applications SET platform = 'ANDROID'
WHERE platform IN ('POG_ANDROID', 'POGPOG');
UPDATE applications SET platform = 'IOS'
WHERE platform = 'POG_IOS';

-- 定时计划的 plan_type 同样带过产品线值
UPDATE test_plans SET plan_type = 'ANDROID'
WHERE plan_type IN ('POG_ANDROID', 'POGPOG');
UPDATE test_plans SET plan_type = 'IOS'
WHERE plan_type = 'POG_IOS';

-- ============ 迁移后核对：应全部为 0 行 ============
SELECT 'ui_elements' AS table_name, COUNT(*) AS remaining FROM ui_elements
WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG')
UNION ALL SELECT 'ui_flows', COUNT(*) FROM ui_flows
WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG')
UNION ALL SELECT 'tasks', COUNT(*) FROM tasks
WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG')
UNION ALL SELECT 'devices', COUNT(*) FROM devices
WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG')
UNION ALL SELECT 'applications', COUNT(*) FROM applications
WHERE platform IN ('POG_ANDROID', 'POG_IOS', 'POGPOG');
