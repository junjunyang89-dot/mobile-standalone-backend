import type { BrowserContext, Page } from '@playwright/test'

export type AuthMode = 'query' | 'cookie' | 'localStorage' | 'bearer' | 'none'

/** 把 token 挂到 URL query（默认参数名 token） */
export function withQueryToken(rawUrl: string, token: string, key = 'token'): string {
  if (!token) return rawUrl
  const u = new URL(rawUrl)
  if (!u.searchParams.get(key)) u.searchParams.set(key, token)
  return u.toString()
}

/**
 * 按模式注入登录态。多数 H5 是 query 或 localStorage；不通就改 .env 里 H5_AUTH_MODE。
 */
export async function applyAuth(
  context: BrowserContext,
  page: Page,
  opts: { token: string; mode: AuthMode; cookieDomain?: string; storageKey?: string }
): Promise<void> {
  const token = (opts.token || '').trim()
  if (!token || opts.mode === 'none') return

  const key = opts.storageKey || 'token'
  const domain = opts.cookieDomain || 'lock.pogpog.gg'

  if (opts.mode === 'cookie') {
    await context.addCookies([{ name: key, value: token, domain, path: '/' }])
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
