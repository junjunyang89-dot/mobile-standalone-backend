# Cursor Ask 桥（主菜单「AI助手」）

Java 后端通过本目录的 `ask.mjs` 调用本机 `@cursor/sdk`。

## 一次性准备

1. 安装依赖（Node ≥ 22.13）：

```bash
cd scripts/cursor-ask && npm install
```

2. 配 API Key（二选一）：

- 环境变量：`export CURSOR_API_KEY=cursor_...`
- 或 `application.yml`：`cursor.ask.api-key: ...`

Key 在 [Cursor Dashboard → Integrations](https://cursor.com/dashboard/integrations)。

3. 确认代码目录：默认 `~/projects/qa_coding/app_coding`，可在 yml 的 `cursor.ask.cwd` 或页面上改。

4. **重启后端**，刷新前端「AI助手」。

## 自测桥接

```bash
echo '{"apiKey":"'"$CURSOR_API_KEY"'","cwd":"'"$HOME"'/projects/qa_coding/app_coding","message":"这个目录里有哪些主要仓库？只列名字"}' \
  | node ask.mjs
```
