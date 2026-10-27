# TestForge 接入：查询结果与完成通知

平台基础地址使用部署后的地址（本机开发为 http://localhost:3080），全部开放接口发送 X-API-Key，不在代码或示例里写入实际密钥。

## 先接查询即可使用

1. POST /api/open/v1/analysis-jobs 创建任务，保存响应中的 id。相同 externalId 与相同请求内容复用原任务；同 externalId 不同内容返回409；新 externalId 创建新任务，不能仅凭JIRA单号复用历史结果。
2. GET /api/open/v1/analysis-jobs/{id} 查询状态。resultReady=true 时获取 resultUrl（相对平台基础地址）；QUEUED/RUNNING 按 pollAfterSeconds 查询；BLOCKED/FAILED/CANCELLED 停止轮询并展示原因，允许用户后续刷新。
3. GET /api/open/v1/analysis-jobs/{id}/result 获取完整JSON。结果未就绪返回409。
4. 验证 analysisContext 中的 jobId、client、requirementKey、businessGroup、materialVersion、requestHash、expectedReportRevision，与TestForge保存的提交记录一致才应用；报告完成不等于测试执行通过。coverageGaps必须展示；manual代表人工复核导入。
5. 保存稳定的问题/API/用例ID及执行记录；未变化的已测项保留，受影响项标记需复测，新增项标记新增。

## 已有PVP-13859结果

任务ID：e3bfb8a2-e69e-4763-80b3-96588e214999
外部ID：PVP-13859-snapshot-1793004339579
结果：GET /api/open/v1/analysis-jobs/e3bfb8a2-e69e-4763-80b3-96588e214999/result
11项问题与风险、13项逻辑接口、57条待执行用例；含部分覆盖说明。不要为读取这份结果重复创建任务。

## 可选：完成通知

TestForge提供固定POST通知地址，由平台部署配置 TESTFORGE_ANALYSIS_CALLBACK_URL 设置，重启平台生效。调用请求不能自定义callbackUrl。

通知头：X-Analysis-Timestamp（Unix秒）、X-Analysis-Event（稳定事件ID）、X-Analysis-Signature（HMAC-SHA256小写十六进制）。签名密钥与该调用方X-API-Key相同。验签原文：timestamp + 换行 + eventId + 换行 + 原始请求体。不能重新序列化JSON后验签。

通知体保留id、external_id、requirement_key、status、result_hash、result_source、callback_event_id、expectedReportRevision、schemaVersion、businessGroup、materialVersion、requestHash，并新增resultReady、resultUrl。TestForge验签、限制时间偏差、事件ID幂等入队持久化后回复2xx，再从平台拉取结果；重复通知安全处理，不重复生成用例。

平台不会把回调2xx等同TestForge业务导入成功。推送失败不影响结果查询。回调未配置的已完成任务，配置后自动补发；失败任务可POST /api/open/v1/analysis-jobs/{id}/retry-callback，只重发通知，不重新分析，且只能操作自己调用方的任务。

结果接口已经存在，TestForge可先接轮询。本机回调已配置为 http://127.0.0.1:3800/api/analysis-bridge/callback（.db-profile.properties）；部署时请用 TESTFORGE_ANALYSIS_CALLBACK_URL 设置TestForge可访问地址。以实际回调响应确认通知是否接收，不能仅凭配置认定跨平台联调完成。
