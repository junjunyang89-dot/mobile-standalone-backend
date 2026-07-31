# 多步骤截图展示 - 验证结果

## 1. 前端配置 ✅ 正确

| 项目 | 配置 | 说明 |
|------|------|------|
| API 基址 | `http://127.0.0.1:3000/api` | 与后端 context-path 一致 |
| 概览接口 | `GET /functional-test/tasks/{id}/overview` | 正确 |
| 步骤截图 URL | `baseUrl + "/" + entry.screenshotUrl` | 格式：`http://127.0.0.1:3000/api/functional-test/tasks/{id}/screenshots/{stepIndex}` |
| 展示逻辑 | `overviewStepScreenshots` 过滤 `executionLog` 中带 `screenshotUrl` 的条目 | 正确 |

## 2. 后端接口 ✅ 正确

| 项目 | 配置 | 说明 |
|------|------|------|
| 端口 | 3000 | application.yml |
| context-path | /api | 完整路径：/api/functional-test/... |
| fillScreenshotUrls | 为有 screenshotPath 的条目填充 screenshotUrl | 格式：`functional-test/tasks/{id}/screenshots/{i}` |
| 步骤截图接口 | `GET /tasks/{id}/screenshots/{stepIndex}` | 返回 image/png |

## 3. 实际 API 验证结果 ⚠️ 发现问题

对任务 `040e3fd3-a064-423a-ac82-174e353d17ea` 的验证：

```
GET /api/functional-test/tasks/040e3fd3-a064-423a-ac82-174e353d17ea/overview
→ executionLog: []  （空数组）

GET /api/functional-test/tasks/040e3fd3-a064-423a-ac82-174e353d17ea/execution-log
→ []  （空数组）
```

**结论：该任务的 `execution_log` 在数据库中为空，导致 overview 返回的 executionLog 为空，前端无法展示多张截图。**

## 4. 根因分析

可能原因：
1. **历史任务**：该任务可能在「保存 execution_log」逻辑上线前执行，当时未写入 execution_log
2. **序列化失败**：TaskService 在 `JSON.writeValueAsString(result.getExecutionLog())` 时抛 JsonProcessingException，导致未写入（会打 warn 日志）
3. **TEXT 字段截断**：execution_log 为 TEXT（约 64KB），若 JSON 超长可能被截断或写入失败

## 5. 建议操作

1. **重新执行一次登录测试**：新建任务或对现有任务重新执行，确保最新执行记录包含 executionLog
2. **检查后端日志**：执行完成后查看是否有「已写入执行日志: taskId=xxx, 条数=42」日志；若有「序列化执行日志失败」则需排查
3. **验证新任务**：执行完成后，用新任务 ID 请求 overview，确认 executionLog 非空且含 screenshotUrl
