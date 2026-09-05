import type { BrowserContext, Page } from '@playwright/test'

export type AuthMode = 'query' | 'cookie' | 'localStorage' | 'bearer' | 'none'

/** 把 token 挂到 URL query（默认参数名 token） */
export function withQueryToken(rawUrl: string, token: string, key = 'token'): string {
  if (!token) return rawUrl
  const u = new URL(rawUrl)
  if (!u.searchParams.get(key)) u.searchParams.set(key, token)
  return u.toString()
}

/** 从页面地址取 host 当 cookie 域；取不到就返回空，交给调用方兜底 */
function hostOf(rawUrl?: string): string {
  if (!rawUrl) return ''
  try {
    return new URL(rawUrl).hostname
  } catch {
    return ''
  }
}

/**
 * 按模式注入登录态。多数 H5 是 query 或 localStorage；不通就改 .env 里 H5_AUTH_MODE。
 */
export async function applyAuth(
  context: BrowserContext,
  page: Page,
  opts: {
    token: string
    mode: AuthMode
    cookieDomain?: string
    storageKey?: string
    /** 被测页面地址，用来在没显式配 cookieDomain 时推出 cookie 域 */
    pageUrl?: string
    /** cookie 模式下要注入的多个 cookie：名 → 值。给了它就不再用单个 token */
    cookies?: Record<string, string>
  }
): Promise<void> {
  const token = (opts.token || '').trim()
  if (!token || opts.mode === 'none') return

  const key = opts.storageKey || 'token'
  // 域名优先从被测页面推出来。原先写死 lock.pogpog.gg，换个站点就注不进去——
  // cookie 域不匹配时浏览器会直接丢弃，页面表现是「填了 token 还是未登录」，
  // 而这中间没有任何报错，最难查
  const domain = opts.cookieDomain || hostOf(opts.pageUrl) || 'lock.pogpog.gg'

  if (opts.mode === 'cookie') {
    // 有些站点的登录态不止一个 cookie。国服战令这类页面要
    // PVP_APP_TOKEN + PVP_APP_STEAMID + PVP_APP_USERID 三个都在才认，
    // 只注 token 的话页面照样停在「去登录」
    const extra = opts.cookies || {}
    const names = Object.keys(extra)
    const list = names.length
      ? names.map((n) => ({ name: n, value: String(extra[n]), domain, path: '/' }))
      : [{ name: key, value: token, domain, path: '/' }]
    await context.addCookies(list)
    return
  }

  if (opts.mode === 'localStorage') {
    await context.addInitScript(
      ({ k, v }) => {
        try {
          localStorage.setItem(k, v)
          localStorage.setItem('access_token', v)
          localStorage.setItem('accessToken', v)
        } catch {
          /* ignore */
        }
      },
      { k: key, v: token }
    )
    return
  }

  if (opts.mode === 'bearer') {
    await page.route('**/*', async (route) => {
      const headers = {
        ...route.request().headers(),
        authorization: `Bearer ${token}`
      }
      await route.continue({ headers })
    })
  }
  // query 模式在 goto 前用 withQueryToken，此处无需处理
}
