# 交付契约

默认输出到用户指定目录；未指定时使用 `analysis/<JIRA-key>/`。文件输出不是平台已经导入的证明。

## current-report.md

先给测试行动：当前业务与覆盖范围、优先必测场景、需求疑问/已确认结论、问题与验证办法、待测接口、用例准备缺项。最后放证据与材料缺口。不要用技术词堆满摘要；需要时展开代码。

## current-analysis.json

以下是字段约定，不是现有 QA 平台可直接接收的 API。平台集成前需要单独映射其接口和校验。

- schemaVersion、requirementKey、revision、generatedAt。
- sources[]：id、type、locator、version/readAt、readMethod、readScope、gaps。
- repos[]：id、requestedRefs、resolvedSHAs、comparisonMethod、dirtyFilesIncluded、reviewedScope、missingScope。
- requirementQuestions[]：id、question、sourceIds、status、confirmationEvidence。
- findings[]：id、title、priority、evidenceLevel、trigger、cause、impact、verification、suggestion、evidence[]、relatedCaseIds、status。
- apis[]：id、service、method、path、gatewayMapping、paramsSource、authRequirements、mustTestReason、relatedCaseIds、readinessGaps。
- cases[]：id、revision、title、priority、category、requirementIds、findingIds、apiIds、preconditions、steps[]、expected[]、dataPreparation、readinessGaps、changeStatus、changeReason、executionRefs[]。
- executionRecords[]：只记录实际执行事实，缺少运行证据时为空；不得用模型预期填入。
- pendingRevisions[]：尚未应用的用例内容修订，包含 originalCaseId、原内容依据、新候选、影响理由、审核状态。
- mergeSummary：新增/调整/待复测/保留/建议停用/待确认场景的 ID 与理由；newMaterialNotAnalyzed。

证据：至少 sourceId；代码证据还需 repoId、commit、relativePath、lineStart/lineEnd、最小相关摘录。接口参数无法确认时留空并写缺项，不编造完整 schema。

changeStatus 可用 new/changed/retest/unchanged/retire-proposed/impact-pending；它不替代实际 executionRecords。首次分析中的用例默认未执行。

## 其他可选文件

- requests.http：已确认的请求结构，环境/身份/数据使用明确占位符或受保护变量；逐项说明补齐条件。默认不自动执行。
- history/：旧报告与依据，保留稳定 ID 和修订关系；不在测试主页面按批次铺开。

## 交付前核对

ID 唯一，问题/用例/接口引用可解析；源码证据能在指定 SHA 定位；不得泄露凭据；无新变化的已测用例保持身份和执行记录；有材料缺口时不宣称全量审完。没有真实读取/执行工具则只交付可完成的部分并列缺口。

## 涉及造数与执行时的扩展

- caseBindings[]：scenarioId、platformCaseId、caseRevision、endpointId、覆盖判断及人工审核状态。
- readiness[]：caseId、environmentId、各项状态、blockers、证据时间；不把配置存在当作可执行。
- fixturePlans[]：planId/hash、策略、环境/数据库引用、业务约束、参数化步骤、影响上限、输出变量引用、清理/补偿、不可逆副作用。
- fixtures[]：fixtureId、operationId、自有资源引用、准备状态和清理状态；不存秘密值。
- executionRecords[] 增加 fixtureId、operationId、各服务 SHA、依赖与业务断言证据、结果分类、清理残留；实际未运行则为空。
- unresolvedActions[]：缺少环境/鉴权/数据/能力，所需补充及是否阻塞执行。

这些字段只为分析交付及后续适配设计；必须根据真实平台响应填充 ID，不生成看起来像已经执行的假 runId/fixtureId。
