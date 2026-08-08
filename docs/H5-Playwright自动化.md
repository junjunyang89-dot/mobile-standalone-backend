# Web 端测试（员工页面编排）

侧栏：**功能测试 → Web 端测试**

## 员工怎么用（不碰代码）

1. **新建用例**：填名称、需求号、页面 URL、鉴权  
2. **添加步骤**：点选关键字（打开 / 点击 / 输入 / 断言文案 / 等接口 / 接口 GET…）  
3. **勾选 → 执行**：看通过/失败，无需改仓库  

## 引擎

后台用 Playwright 执行 `scripts/h5-playwright/tests/step-run.spec.ts`。

| 关键字 | 作用 |
|--------|------|
| OPEN_URL | 打开页面（带 token） |
| CLICK / FILL | 点击、输入 |
| ASSERT_TEXT / ASSERT_URL | UI 断言 |
| WAIT_RESPONSE / API_GET | 接口等待 / 直接调接口断言 |

小程序：同入口占位，需单独引擎，本期不跑。

## API

- `GET /api/web-test/status|keywords|cases`
- `POST/PUT/DELETE /api/web-test/cases`
- `POST /api/web-test/run` body: `{ "caseIds": ["wtc-xxx"], "tokenOverride": "可选" }`
