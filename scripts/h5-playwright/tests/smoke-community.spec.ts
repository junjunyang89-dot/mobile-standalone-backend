import { test, expect } from '@playwright/test'

/**
 * 冒烟：打开平台种子页「社区帖详情」（DOMESTIC_ESPORTS）。
 * 与 ui_h5_pages 种子 URL 对齐；有 token 时挂 query 再打开。
 */
const DEFAULT_URL =
  'https://news.wmpvp.com/community-detail.html?id=380194997&qq_aio_chat_type=2'

function buildUrl(): string {
  const raw = process.env.H5_COMMUNITY_DETAIL_URL || DEFAULT_URL
  const token = (process.env.H5_AUTH_TOKEN || '').trim()
  if (!token) return raw
  const u = new URL(raw)
  if (!u.searchParams.get('token')) {
    u.searchParams.set('token', token)
  }
  return u.toString()
}

test.describe('国内 H5 冒烟 · 社区帖详情', () => {
  test('页面可打开且文档可加载', async ({ page }, testInfo) => {
    const url = buildUrl()
    const resp = await page.goto(url, { waitUntil: 'domcontentloaded' })

    // 允许业务页非 2xx（如未登录跳转），但网络层要有响应
    expect(resp, '应有 HTTP 响应').toBeTruthy()
    const status = resp!.status()
    expect(status, `HTTP ${status}`).toBeLessThan(500)

    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {
      /* 长轮询页可能永不 idle，忽略 */
    })

    const title = await page.title()
    await page.screenshot({
      path: testInfo.outputPath('community-detail.png'),
      fullPage: true
    })

    // 未登录时可能几乎无可见文案（SPA 壳）；至少要有 HTML 骨架
    const html = await page.content()
    expect(html.length, 'HTML 不应为空').toBeGreaterThan(200)
    await expect(page.locator('html')).toBeVisible()

    testInfo.annotations.push({
      type: 'note',
      description: `url=${url} title=${title || '(empty)'} status=${status} token=${process.env.H5_AUTH_TOKEN ? 'yes' : 'no'}`
    })
  })

  test('关键资源未全部 5xx（粗检）', async ({ page }) => {
    const failed: string[] = []
    page.on('response', (r) => {
      if (r.status() >= 500) failed.push(`${r.status()} ${r.url()}`)
    })
    await page.goto(buildUrl(), { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(3000)
    expect(failed.slice(0, 5), `存在 5xx: ${failed.slice(0, 5).join('; ')}`).toHaveLength(0)
  })
})
