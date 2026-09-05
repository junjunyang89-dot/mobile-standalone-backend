-- =====================================================================
-- H5 页面：Cookie 登录态
-- =====================================================================
-- 为什么加这一列
-- ---------------------------------------------------------------------
-- 实测「2026 S3 国服战令」这类活动页（news-staging.wmpvp.com:8039/v3/
-- act-seasonBenefit.html）：静态资源全部加载成功，但一个接口请求都没有，
-- 页面停在「去登录」。
--
-- 原因在前端 bundle 里写得很清楚——登录态是从 Cookie 读的，不是 query：
--   c.get("PVP_APP_TOKEN") / c.get("PVP_APP_STEAMID") / c.get("PVP_APP_USERID")
--   interceptors.request.use(e => (e.headers.token = c.get("PVP_APP_TOKEN"), e))
--
-- 而 ui_h5_pages.auth_type 只有 NONE / TOKEN_QUERY / MANUAL，auth_token 也只存
-- 得下一个值。方式对不上，所以「填了 token 还是没登录」——而且中间没有任何报错。
--
-- auth_cookies 存一个 JSON 对象（名 → 值），一次注入多个：
--   {"PVP_APP_TOKEN":"...","PVP_APP_STEAMID":"...","PVP_APP_USERID":"..."}
-- 只注 token 一个是不够的，那三个都在页面才认。
-- =====================================================================

SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_h5_pages'
             AND COLUMN_NAME = 'auth_cookies');
SET @s := IF(@c = 0,
  'ALTER TABLE ui_h5_pages ADD COLUMN auth_cookies VARCHAR(2048) NULL COMMENT ''Cookie 登录态，JSON 对象：名→值'' AFTER auth_token',
  'SELECT ''auth_cookies 已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- cookie 域。留空时由 Playwright 按被测页面的 host 推——
-- 域不匹配浏览器会直接丢弃 cookie，页面表现就是「填了还是未登录」，且不报错
SET @c := (SELECT COUNT(1) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_h5_pages'
             AND COLUMN_NAME = 'cookie_domain');
SET @s := IF(@c = 0,
  'ALTER TABLE ui_h5_pages ADD COLUMN cookie_domain VARCHAR(128) NULL COMMENT ''Cookie 域；留空按页面 host 推'' AFTER auth_cookies',
  'SELECT ''cookie_domain 已存在''');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;


-- ---------------------------------------------------------------------
-- 验收
-- ---------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ui_h5_pages'
  AND COLUMN_NAME IN ('auth_type', 'auth_key', 'auth_token', 'auth_cookies', 'cookie_domain')
ORDER BY ORDINAL_POSITION;

SELECT id, name, auth_type, IF(auth_cookies IS NULL, '未配', '已配') AS cookie登录态
FROM ui_h5_pages ORDER BY sort_order;


-- =====================================================================
-- 回滚
-- =====================================================================
-- ALTER TABLE ui_h5_pages DROP COLUMN cookie_domain, DROP COLUMN auth_cookies;
-- =====================================================================
