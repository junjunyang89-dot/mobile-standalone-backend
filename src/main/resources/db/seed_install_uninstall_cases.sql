-- 清理旧格式安装/卸载种子用例（case_id 非 TC-XXX 格式），让 Java 侧 @PostConstruct 重新以 TC-XXX 写入
-- 可重复执行：只删 case_id 以 iOS-INSTALL- / ANDROID-INSTALL- / ANDROID-UNINSTALL- / iOS-UNINSTALL- 开头的种子数据
DELETE FROM ai_test_cases
WHERE source = 'MANUAL'
  AND created_by = 'seed'
  AND (
    case_id LIKE 'iOS-INSTALL-%'
    OR case_id LIKE 'iOS-UNINSTALL-%'
    OR case_id LIKE 'ANDROID-INSTALL-%'
    OR case_id LIKE 'ANDROID-UNINSTALL-%'
  );
