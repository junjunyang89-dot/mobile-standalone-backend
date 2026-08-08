import { test, expect } from '@playwright/test'
import { applyAuth, withQueryToken, type AuthMode } from './helpers/auth'

/**
 * PogPog Deadlock 玩家周报 H5（示例页）。
 * URL / token 只从环境变量读取，勿把真实 token 写进仓库。
 *
 * .env 示例：
 *   H5_POGPOG_REPORT_URL=https://lock.pogpog.gg/players/.../report?week=2026-06-29
 *   H5_AUTH_TOKEN=你的token
 *   H5_AUTH_MODE=query          # query | localStorage | cookie | bearer
 */
const DEFAULT_URL =
  'https://lock.pogpog.gg/players/76561199278980753/report?week=2026-06-29'

function authMode(): AuthMode {
  const m = (process.env.H5_AUTH_MODE || 'query').trim().toLowerCase()
  if (m === 'cookie' || m === 'localstorage' || m === 'bearer' || m === 'none' || m === 'query') {
    return (m === 'localstorage' ? 'localStorage' : m) as AuthMode
  }
  return 'query'
}

function targetUrl(): string {
  const raw = (process.env.H5_POGPOG_REPORT_URL || DEFAULT_URL).trim()
  const token = (process.env.H5_AUTH_TOKEN || '').trim()
  const mode = authMode()
  if (mode === 'query' && token) return withQueryToken(raw, token, process.env.H5_AUTH_QUERY_KEY || 'token')
  return raw
}

test.describe('PogPog H5 · 玩家周报', () => {
  test('带登录态打开周报页', async ({ page, context }, testInfo) => {
    const token = (process.env.H5_AUTH_TOKEN || '').trim()
    test.skip(!token, '未设置 H5_AUTH_TOKEN，跳过（请在 scripts/h5-playwright/.env 填写）')

    const mode = authMode()
    await applyAuth(context, page, {
      token,
      mode,
      cookieDomain: 'lock.pogpog.gg'
    })

    const url = targetUrl()
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded' })
    expect(resp, '应有 HTTP 响应').toBeTruthy()
    expect(resp!.status()).toBeLessThan(500)

    await page.waitForLoadState('networkidle', { timeout: 25_000 }).catch(() => {})
    await page.waitForTimeout(2000)

    const title = await page.title()
    await page.screenshot({
      path: testInfo.outputPath('pogpog-report.png'),
      fullPage: true
    })

    const html = await page.content()
    expect(html.length).toBeGreaterThan(200)

    const bodyText = ((await page.locator('body').innerText().catch(() => '')) || '').trim()
    // SPA 未登录时可能几乎无文案；有 token 时尽量应有可见内容
    testInfo.annotations.push({
      type: 'note',
      description: `mode=${mode} title=${title} bodyLen=${bodyText.length} (token 已注入，未打印)`
    })

    // 粗断言：不要整页只有空白壳且标题异常；Deadlock 标题常见
    expect(title.toLowerCase().length).toBeGreaterThan(0)
  })
})
