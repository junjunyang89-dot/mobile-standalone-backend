import { test, expect } from '@playwright/test'

/**
 * 通用模板：用环境变量 H5_SMOKE_URL 测任意国内 H5。
 * 例：H5_SMOKE_URL=https://xxx.com/page.html npm test -- smoke-url
 */
test.describe('通用 H5 URL 冒烟', () => {
  test('打开 H5_SMOKE_URL', async ({ page }, testInfo) => {
    const url = (process.env.H5_SMOKE_URL || '').trim()
    test.skip(!url, '未设置 H5_SMOKE_URL，跳过')

    const resp = await page.goto(url, { waitUntil: 'domcontentloaded' })
    expect(resp).toBeTruthy()
    expect(resp!.status()).toBeLessThan(500)

    await page.screenshot({ path: testInfo.outputPath('smoke-url.png'), fullPage: true })
    const text = ((await page.locator('body').innerText().catch(() => '')) || '').trim()
    expect(text.length).toBeGreaterThan(0)
  })
})
