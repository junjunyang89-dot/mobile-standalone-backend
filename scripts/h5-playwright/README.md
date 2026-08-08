# 国内 H5 / 网站 Playwright（与 Appium 分离）

能单独用浏览器打开的页面，用本目录自动化；必须在 App WebView 里测的，继续走平台 Appium。

## 快速开始

```bash
cd scripts/h5-playwright
npm install
npx playwright install chromium
cp .env.example .env   # 需要登录态时填 H5_AUTH_TOKEN

npm test               # 无头跑
npm run test:headed    # 有界面
npm run report         # 看 HTML 报告
```

只跑社区帖详情：

```bash
npx playwright test smoke-community
```

测任意 URL：

```bash
H5_SMOKE_URL='https://你的页面' npx playwright test smoke-url
```

## 需要 token 的页面

1. 在 `.env` 填写（参考 `.env.example`，**不要提交 .env**）：

```bash
H5_AUTH_TOKEN=你的token
H5_AUTH_MODE=query          # 或 localStorage / cookie / bearer
H5_POGPOG_REPORT_URL=https://lock.pogpog.gg/players/.../report?week=...
```

2. 跑 PogPog 周报冒烟：

```bash
npx playwright test smoke-pogpog-report --project=chromium
```

若页面仍像未登录：把 `H5_AUTH_MODE` 改成 `localStorage` 或 `cookie` / `bearer` 再试（看前端怎么读登录态）。

## 与平台关系

| 平台「H5 页面」 | 本目录 Playwright |
|----------------|-------------------|
| URL / token 台账、iframe 预览 | 真实点击/打开/截图自动化 |
| 种子：社区帖详情 `news.wmpvp.com/...` | `tests/smoke-community.spec.ts` 对齐该 URL |

后续可接到后台：选 `ui_h5_pages` → 调本脚本 → 报告回传（尚未做）。

## 推荐扩展顺序

1. 把国内必测 H5 写进 `ui_h5_pages` + 本目录对应用例  
2. 有登录态的补 `H5_AUTH_TOKEN` 或 cookie 注入  
3. 再接到平台「一键跑 H5」
