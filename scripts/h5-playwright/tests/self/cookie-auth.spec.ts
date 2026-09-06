import { test, expect } from '@playwright/test'
import { applyAuth } from '../helpers/auth'

/**
 * Cookie 登录态注入自测。不依赖真实登录凭据，用假值验证管道本身。
 *
 * 盯的是两个「静默失效」：cookie 注不进去、或注到了错的域上。两者的症状都是
 * 页面停在未登录，跟 token 过期长得一模一样，没有测试兜住的话每次都要从头查。
 */
const PAGE = 'http://example.com/some/activity.html'

test('cookie 模式在没有独立 token 时也要注入', async ({ page }) => {
  // 这类页面的登录态整个在 cookie 里，token 字段是空的。
  // 早期实现「token 为空就 return」会把整类配置挡掉，且不留痕迹
  await applyAuth(page.context(), page, {
    token: '',
    mode: 'cookie',
    pageUrl: PAGE,
    cookies: { A_TOKEN: 'v1', A_USER: 'v2', A_EXTRA: 'v3' }
  })

  const got = await page.context().cookies('http://example.com')
  expect(got.map((c) => c.name).sort()).toEqual(['A_EXTRA', 'A_TOKEN', 'A_USER'])
})

test('没配 cookieDomain 时按页面地址推导', async ({ page }) => {
  // 域不匹配浏览器会直接丢弃 cookie，不报错。早期默认域写死成 lock.pogpog.gg，
  // 换个站点就全丢了
  await applyAuth(page.context(), page, {
    token: '',
    mode: 'cookie',
    pageUrl: PAGE,
    cookies: { A_TOKEN: 'v1' }
  })

  const got = await page.context().cookies('http://example.com')
  expect(got).toHaveLength(1)
  expect(got[0].domain).toContain('example.com')
})

test('既没 token 也没 cookie 时不做任何注入', async ({ page }) => {
  await applyAuth(page.context(), page, { token: '', mode: 'cookie', pageUrl: PAGE })
  expect(await page.context().cookies('http://example.com')).toHaveLength(0)
})
