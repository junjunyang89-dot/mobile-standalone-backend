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
  /** Cookie 登录态：名 → 值。有些活动页要好几个 cookie 都在才认 */
  authCookies?: Record<string, string>
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
  ctx: {
    pageUrl?: string
    authMode: AuthMode
    token: string
    authKey: string
    cookieDomain?: string
    /** cookie 模式下的多个登录态 cookie：名 → 值 */
    authCookies?: Record<string, string>
  }
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
        // 不给 cookieDomain 时按被测页面的 host 推，别再落到写死的默认域上
        pageUrl: url,
        storageKey: 'token',
        cookies: p.authCookies || ctx.authCookies
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
    case 'ASSERT_REWARD_ORDER': {
      // 活动页每期换皮、奖品全换，但展示优先级是同一条规则——所以这是这类页面里
      // 少数几个写一次能长期用的断言，而且跨环境：测试和生产配的奖品不同，排法一样。
      const groupSel = String(p.groupSelector || '')
      const itemSel = String(p.itemSelector || '')
      if (!groupSel || !itemSel) {
        throw new Error('ASSERT_REWARD_ORDER 需要 params.groupSelector 与 params.itemSelector')
      }
      const tiers: string[][] = Array.isArray(p.tiers) && p.tiers.length
        ? p.tiers.map((t: any) => (Array.isArray(t) ? t.map(String) : [String(t)]))
        : []
      if (!tiers.length) throw new Error('ASSERT_REWARD_ORDER 需要 params.tiers（优先级阶梯）')
      const aliases: Record<string, string> = (p.aliases && typeof p.aliases === 'object') ? p.aliases : {}
      const fallback = tiers.length - 1

      // 去掉数量与时效修饰：大会员(31天) → 大会员、段位保护盾x2 → 段位保护盾。
      // 括号里装的是时效或说明，不参与类目判断
      const normalize = (raw: string) => {
        let t = (raw || '').trim().replace(/[（(\[][^）)\]]*[）)\]]/g, '')
        const m = t.match(/^(.*?)[xX*×]\s*\d+$/)
        if (m) t = m[1]
        return t.trim()
      }
      // 取最长匹配：类目之间有包含关系时，短的先命中会把级别判错
      const categoryOf = (raw: string) => {
        const norm = normalize(raw)
        const alias = aliases[norm]
        if (alias) return alias
        let best: string | null = null
        for (const tier of tiers) {
          for (const cat of tier) {
            if (cat === '其他') continue
            if (norm.includes(cat) && (!best || cat.length > best.length)) best = cat
          }
        }
        return best
      }
      const tierOf = (cat: string | null) => {
        if (!cat) return fallback
        const i = tiers.findIndex((t) => t.includes(cat))
        return i < 0 ? fallback : i
      }

      const groups = await page.locator(groupSel).all()
      if (!groups.length) throw new Error(`ASSERT_REWARD_ORDER 没匹配到任何奖励分组: ${groupSel}`)

      const problems: string[] = []
      const unmatched: string[] = []
      for (let g = 0; g < groups.length; g++) {
        const names = (await groups[g].locator(itemSel).allTextContents())
          .map((t) => t.replace(/\s+/g, ' ').trim())
          .filter(Boolean)
        const items = names.map((n) => {
          const cat = categoryOf(n)
          if (!cat) unmatched.push(n)
          return { raw: n, cat, tier: tierOf(cat) }
        })
        for (let i = 1; i < items.length; i++) {
          let worst: typeof items[0] | null = null
          for (let j = 0; j < i; j++) {
            if (items[j].tier > items[i].tier && (!worst || items[j].tier > worst.tier)) worst = items[j]
          }
          if (worst) {
            // 直接引用规则片段而不是报「第几级」：人要核对的就是文档里那句
            // 「大会员 > 段位保护盾」，报级数还得回去把阶梯数一遍
            const nameOf = (it: typeof items[0]) =>
              it.cat ? it.cat : `${it.raw}(未匹配，按「其他」)`
            problems.push(
              `第 ${g + 1} 组：「${items[i].raw}」应排在「${worst.raw}」前面` +
              `——按优先级：${nameOf(items[i])} > ${nameOf(worst)}`
            )
          }
        }
      }
      // 认不出的单独提示，不算失败：静默归到「其他」会让排错看不出来，
      // 但「进房特效算不算进房音效」只有人能定，这里只负责说出来
      if (unmatched.length) {
        console.log(`[ASSERT_REWARD_ORDER] 未匹配到类目（已按「其他」处理）: ${[...new Set(unmatched)].join('、')}`)
      }
      expect(problems.join('\n'), '奖励展示顺序').toBe('')
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
        cookieDomain: c.cookieDomain,
        authCookies: c.authCookies
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
