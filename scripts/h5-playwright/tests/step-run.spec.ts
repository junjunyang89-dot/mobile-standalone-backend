import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
import { applyAuth, withQueryToken, type AuthMode } from './helpers/auth'

type Step = {
  keywordCode: string
  params?: Record<string, any>
}

type CasePayload = {
  id?: string
  name: string
  pageUrl?: string
  authMode?: string
  authToken?: string
  authQueryKey?: string
  cookieDomain?: string
  steps: Step[]
}

function loadCases(): CasePayload[] {
  const raw = process.env.WEB_TEST_CASES_JSON || '[]'
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function resolveSelector(raw: string): string {
  const s = (raw || '').trim()
  if (!s) return s
  if (s.startsWith('text=') || s.startsWith('css=') || s.startsWith('xpath=') || s.startsWith('role=')) {
    return s
  }
  if (s.startsWith('/') || s.startsWith('(')) return `xpath=${s}`
  if (s.startsWith('#') || s.startsWith('.') || s.includes('[')) return s
  return `text=${s}`
}

async function runStep(
  page: Page,
  request: APIRequestContext,
  step: Step,
  ctx: { pageUrl?: string; authMode: AuthMode; token: string; authKey: string; cookieDomain?: string }
) {
  const code = (step.keywordCode || '').toUpperCase()
  const p = step.params || {}

  switch (code) {
    case 'OPEN_URL': {
      let url = (p.url || ctx.pageUrl || '').trim()
      if (!url) throw new Error('OPEN_URL 缺少 url / pageUrl')
      const mode = (p.authMode || ctx.authMode || 'none') as AuthMode
      const token = (p.token || ctx.token || '').trim()
      const key = p.authQueryKey || ctx.authKey || 'token'
      await applyAuth(page.context(), page, {
        token,
        mode: mode === 'query' ? 'none' : mode,
        cookieDomain: p.cookieDomain || ctx.cookieDomain,
        storageKey: 'token'
      })
      if (mode === 'query' && token) url = withQueryToken(url, token, key)
      const resp = await page.goto(url, { waitUntil: 'domcontentloaded' })
      expect(resp, '应有 HTTP 响应').toBeTruthy()
      expect(resp!.status()).toBeLessThan(500)
      return
    }
    case 'CLICK': {
      const sel = resolveSelector(String(p.selector || p.text || ''))
      if (!sel) throw new Error('CLICK 需要 params.selector')
      await page.locator(sel).first().click({ timeout: (p.timeoutSec || 15) * 1000 })
      return
    }
    case 'FILL': {
      const sel = resolveSelector(String(p.selector || ''))
      if (!sel) throw new Error('FILL 需要 params.selector')
      await page.locator(sel).first().fill(String(p.value ?? ''), { timeout: (p.timeoutSec || 15) * 1000 })
      return
    }
    case 'WAIT_MS': {
      await page.waitForTimeout(Number(p.ms || 1000))
      return
    }
    case 'WAIT_TEXT': {
      const text = String(p.text || '')
      if (!text) throw new Error('WAIT_TEXT 需要 params.text')
      await expect(page.getByText(text).first()).toBeVisible({ timeout: (p.timeoutSec || 15) * 1000 })
      return
    }
    case 'ASSERT_TEXT': {
      const text = String(p.text || '')
      if (!text) throw new Error('ASSERT_TEXT 需要 params.text')
      await expect(page.getByText(text).first()).toBeVisible({ timeout: (p.timeoutSec || 15) * 1000 })
      return
    }
    case 'ASSERT_URL': {
      const contains = String(p.contains || '')
      if (!contains) throw new Error('ASSERT_URL 需要 params.contains')
      await expect(page).toHaveURL(new RegExp(contains.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), {
        timeout: (p.timeoutSec || 10) * 1000
      })
      return
    }
    case 'ASSERT_TITLE_NOT_EMPTY': {
      const title = await page.title()
      expect((title || '').trim().length).toBeGreaterThan(0)
      return
    }
    case 'ASSERT_HTML_MIN': {
      const min = Number(p.minLength || 200)
      const html = await page.content()
      expect(html.length).toBeGreaterThan(min)
      return
    }
    case 'WAIT_RESPONSE': {
      const includes = String(p.urlIncludes || '')
      if (!includes) throw new Error('WAIT_RESPONSE 需要 params.urlIncludes')
      const status = p.status != null ? Number(p.status) : undefined
      const timeout = (p.timeoutSec || 20) * 1000
      const resp = await page.waitForResponse(
        (r) => r.url().includes(includes) && (status == null || r.status() === status),
        { timeout }
      )
      expect(resp.ok() || (status != null && resp.status() === status)).toBeTruthy()
      return
    }
    case 'API_GET': {
      const url = String(p.url || '')
      if (!url) throw new Error('API_GET 需要 params.url')
      const headers: Record<string, string> = {}
      if (ctx.token) headers['Authorization'] = `Bearer ${ctx.token}`
      if (p.headers && typeof p.headers === 'object') Object.assign(headers, p.headers)
      const resp = await request.get(url, { headers })
      const expectStatus = Number(p.expectStatus || 200)
      expect(resp.status(), `API ${url}`).toBe(expectStatus)
      if (p.expectBodyIncludes) {
        const body = await resp.text()
        expect(body).toContain(String(p.expectBodyIncludes))
      }
      return
    }
    default:
      throw new Error(`不支持的关键字: ${code}`)
  }
}

const cases = loadCases()

test.describe('平台步骤用例', () => {
  if (!cases.length) {
    test('无用例', async () => {
      test.skip(true, 'WEB_TEST_CASES_JSON 为空')
    })
    return
  }

  for (const c of cases) {
    test(c.name || c.id || 'unnamed', async ({ page, request }, testInfo) => {
      const ctx = {
        pageUrl: c.pageUrl,
        authMode: (c.authMode || 'none') as AuthMode,
        token: (c.authToken || '').trim(),
        authKey: c.authQueryKey || 'token',
        cookieDomain: c.cookieDomain
      }
      const steps = Array.isArray(c.steps) ? c.steps : []
      expect(steps.length, '用例至少一步').toBeGreaterThan(0)

      for (let i = 0; i < steps.length; i++) {
        const step = steps[i]
        try {
          await runStep(page, request, step, ctx)
        } catch (e: any) {
          await page.screenshot({ path: testInfo.outputPath(`fail-step-${i + 1}.png`), fullPage: true }).catch(() => {})
          throw new Error(`步骤${i + 1} ${step.keywordCode} 失败: ${e?.message || e}`)
        }
      }
      await page.screenshot({ path: testInfo.outputPath('done.png'), fullPage: true }).catch(() => {})
    })
  }
})
