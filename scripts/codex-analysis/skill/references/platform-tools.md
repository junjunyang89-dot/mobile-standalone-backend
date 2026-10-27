# Cursor 与 QA 平台的工具契约

这是后续 MCP/API 适配的设计，不是已经部署的工具。先发现实际工具；没有工具时输出准备计划/用例草稿和阻塞项，不编造 tool call、成功响应或数据库结果。不修改同事的 AI 接口自动化模块。

## 适配原则

Skill 负责推理，平台负责凭据、权限、环境路由、幂等、数据归属、执行、审计和持久化。工具名称可以按实际适配器变化，下表的名称只是逻辑能力。

客户端不应绕过平台执行器，另外拿数据库管理员账号执行 SQL 或裸 HTTP。若用户明确选择本地执行，应说明无法自动获得平台能力/审计，并遵守相同的数据和权限范围。

### 读取能力

| 逻辑能力（拟议） | 输入 | 关键输出 |
| --- | --- | --- |
| analysis_get | requirementId | revision、确认规则、源材料、仓库 SHA、接口、场景、已有执行关联 |
| cases_find | projectId、method、normalizedPath、requirementId | 用例及修订、场景覆盖、人工编辑标记、环境绑定、运行摘要 |
| environment_inspect | environmentId、projectId | 环境类型、允许服务、变量键及缺项、credentialRef；不返回秘密 |
| db_schema_read | databaseRef、tables[] | 实际字段/索引/约束/结构版本；限制可见库表 |
| data_query_preview | databaseRef、参数化只读查询或受控查询模板、rowLimit | 脱敏行、耗时和截断信息，不默认拉全表 |
| runs_get | runId 或 caseId+上下文 | 原始执行事实、断言、脱敏请求/响应、依赖结果、清理状态 |

“只读 SQL”不等于任意 SELECT 都安全：使用只读账号、受控模板/AST 校验、表白名单、行数/耗时限制，防止锁、存储函数副作用和敏感字段泄露。

### 准备与写入能力

| 逻辑能力（拟议） | 输入 | 关键输出 |
| --- | --- | --- |
| case_revision_prepare | stableCaseId（新增时为空）、expectedRevision、来源、请求/前置/断言候选 | 可审查差异、缺项、影响场景；不覆盖现用例 |
| case_revision_apply | proposalId、expectedRevision、operationId | 新修订与稳定 ID；保留旧内容、执行记录、人工修改 |
| fixture_plan | 环境/数据库引用、场景约束、生成策略、限制 | 可审查数据计划、影响对象、预期行数、清理与不可逆风险 |
| fixture_apply | planId、planHash、environmentId、operationId、授权上下文 | fixtureId、资源归属、变量引用、逐步结果；不回传秘密 |
| fixture_cleanup | fixtureId、operationId | 本次自有资源清理明细、未清理资源及原因 |
| readiness_check | caseRevision、environmentId、fixtureId、代码版本 | 逐项 ready/missing/unverified、明确执行阻塞 |
| cases_execute | caseIds+修订、环境、fixture、readinessHash、operationId、授权上下文 | runId；异步执行由 runs_get 查询 |
| analysis_link_results | requirementId、expectedAnalysisRevision、场景-平台用例-执行映射 | 同一份当前结果的更新、冲突或无法关联的原因 |

授权上下文由平台会话/策略产生，不由模型自造 `approved: true`。许可应绑定环境、操作种类、计划 hash、数据范围、有效期；读权限不隐含造数和执行权限。执行前重新检查环境/计划是否发生变化，阻塞生产或未授权环境的写操作。

operationId 绑定请求指纹。重复请求返回同一操作结果；同 ID 不同内容返回冲突。执行/造数超时返回 operationId 与 unknown 状态，先查询已有结果，不能启动新的非幂等操作。

所有写入使用版本前提，冲突返回当前版本且不覆盖。工具结果统一包含 status、operationId、数据、缺项、脱敏证据引用和错误分类。不能把接口返回 `ok` 当作业务断言通过。

## 当前仓库可复用能力（2026-10-24 源码核对）

以下是已有 HTTP 路由，需加登录、范围校验和适配层后再暴露给 Agent；不代表已有上述完整 MCP 功能：

- `/api/api-diff-v2/review-reports/:key`：读取导入报告；目前只有已登记报告。
- `GET /api/api-cases/cases?repoId=...`、`GET /api/api-cases/cases/:id`：读取用例。
- `POST /api/api-cases/cases`、`PUT /api/api-cases/cases/:id`：用例增改；不能直接假设具备完整修订审批、乐观锁和幂等。
- `GET /api/api-cases/environments`：环境配置。Agent 适配层仅输出必要元数据，过滤可能包含的凭据。
- `POST /api/api-cases/cases/:id/run`、`POST /api/api-cases/collections/:id/run`：已有执行能力，需核对环境解析、授权和副作用控制。
- `GET /api/api-cases/cases/:id/history`：历史执行记录。
- `/api/services/db-connections`：连接配置；不等于已有安全的通用造数 API。

尚待实现/核对：最小权限 Agent 访问、字段脱敏、受控元数据/查询、数据计划与归属、操作幂等、用例修订审批、执行前逐项检查、分析版本与结果映射。不能仅用现有 CRUD 接口拼接就宣称闭环完成。

## 最小联调验收

在隔离测试环境选一个无真实财产/渠道副作用的接口：
1. 匹配已有用例，准备新场景候选；不复制无关用例、不改人工字段。
2. 缺少鉴权或数据时准确阻塞，不伪造通过。
3. 造数预览及执行范围一致，重复 operationId 不重复创建。
4. 执行后验证业务断言，记录实际环境与代码版本；未知超时不盲重试。
5. 精确清理自有 fixture，原数据保持原样。
6. 回写同一份分析结果；新的不相关变更不重置已有执行记录，相关变更只标受影响场景待复测。

APP-9119 核销涉及权益和渠道，一开始用模拟卡券/模拟渠道或单独隔离资源验证，不把真券当验收工具。实际业务规则以产品确认和最新代码为准，不硬编码旧示例中的数量预期。
