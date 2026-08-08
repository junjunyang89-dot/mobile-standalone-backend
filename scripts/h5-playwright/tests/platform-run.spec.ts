import { test, expect } from '@playwright/test'
import { applyAuth, withQueryToken, type AuthMode } from './helpers/auth'

type PlatformCase = {
  name: string
  url: string
  authMode?: AuthMode
  token?: string
  authQueryKey?: string
  cookieDomain?: string
}

function loadCases(): PlatformCase[] {
  const raw = process.env.WEB_TEST_CASES_JSON || '[]'
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

const cases = loadCases()

test.describe('平台触发 · Web/H5', () => {
  if (cases.length === 0) {
    test('无用例（请由平台传入 WEB_TEST_CASES_JSON）', async () => {
      test.skip(true, 'WEB_TEST_CASES_JSON 为空')
    })
    return
  }

  for (const c of cases) {
    test(c.name || c.url, async ({ page, context }, testInfo) => {
      expect(c.url, 'url 不能为空').toBeTruthy()
      const mode: AuthMode = (c.authMode || (c.token ? 'query' : 'none')) as AuthMode
      const token = (c.token || '').trim()

      await applyAuth(context, page, {
        token,
        mode: mode === 'query' ? 'none' : mode,
        cookieDomain: c.cookieDomain,
        storageKey: 'token'
      })

      const url =
        mode === 'query' && token
          ? withQueryToken(c.url, token, c.authQueryKey || 'token')
          : c.url

      const resp = await page.goto(url, { waitUntil: 'domcontentloaded' })
      expect(resp, '应有 HTTP 响应').toBeTruthy()
      expect(resp!.status(), `HTTP ${resp!.status()}`).toBeLessThan(500)

      await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {})
      await page.screenshot({
        path: testInfo.outputPath('shot.png'),
        fullPage: true
      })

      const html = await page.content()
      expect(html.length, 'HTML 不应为空').toBeGreaterThan(200)
    })
  }
})
