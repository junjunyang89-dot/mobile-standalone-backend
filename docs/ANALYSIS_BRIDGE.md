# TestForge 分析任务桥接

新增菜单：分析任务。保留原 AI 助手和 UI 自动化功能。该入口用于接收、查看、取消分析任务，查看结果以及重试回传。TestForge 仍是测试人员查看完整测试报告的主入口。

## 启动准备

后端 Java 17；执行端 Node 18 或以上。前后端沿用各自原来的启动方式。新增表只有 `analysis_bridge_jobs`，完整结构见同目录 `analysis-bridge.sql`。平台数据库账号有建表权限时首次访问会自动创建；也可以先手工执行 SQL。不会修改业务项目的表。

在后端根目录安装执行端依赖：

```sh
cd scripts/codex-analysis
npm ci
```

执行端采用官方 `@openai/codex-sdk`（锁定 0.164.0）。请为运行后端的系统用户配置独立、可用的 Codex 登录及模型访问环境。它不会复用当前桌面聊天的上下文、登录、浏览器或 Figma 工具。内网资料是否可读取取决于执行机的网络、授权和工具配置；无法读取的材料必须记入 coverageGaps。仅提供一个 Figma 链接并不能保证读取成功，可先补充导出的需求文字和截图材料。

下面配置合并进现有配置，不要覆盖原有 open-api 调用方或仓库配置。密钥从环境变量提供，不要提交真实密钥。

```yaml
open-api:
  clients:
    - name: testforge
      key: ${TESTFORGE_ANALYSIS_API_KEY:}
      enabled: true
analysis-bridge:
  enabled: false
  script: scripts/codex-analysis/run.mjs
  workspace: data/analysis-jobs
  allowed-clients:
    - testforge
  repositories:
    app-engine: /absolute/server/path/app-engine
  # TestForge 实现接收器后再填写固定回传地址；不配置也可以轮询获取结果
  # callbacks:
  #   testforge: http://testforge-host/api/your-analysis-receiver
```

后端进程应从后端根目录启动，或把 script/workspace 改成绝对路径。repositories 是运行后端那台机器的目录映射，不是访问者电脑的路径。当前版本只读取已有本地 Git 引用，不自动拉取远程仓库。先同步需要分析的基线和提测分支，再提交任务。配置完成后将 enabled 改为 true 并重启；默认 false 时任务为 BLOCKED，可在页面配置好后重试，或由管理员导入已有分析 JSON。

Codex 采用只读沙箱，不允许交互式批准。当前版本生成分析、测试用例和数据准备建议，不执行测试请求或写入业务数据库。需要的数据库结构、接口定义、需求内容应作为任务材料提供；未来数据库工具必须另外配置授权，不能把有数据库权限当作已经完成测试。

## TestForge 调用协议

API 前缀为 `/api/open/v1/analysis-jobs`，使用 `X-API-Key` 请求头。调用方只能访问自己的任务。

- POST `/`：创建，返回 202 和任务 ID。
- GET `/`：最近 100 个任务，可用 `externalId` 精确查询。
- GET `/{id}`：查询状态。
- GET `/{id}/result`：成功后获取 JSON；未完成返回 409。
- POST `/{id}/cancel`：取消未完成任务。

创建请求例子：

```json
{
  "externalId": "APP-9119-analysis-001",
  "requirementKey": "APP-9119",
  "expectedReportRevision": "revision-001",
  "changeNotes": "分析核销、库存、重试和前后端交互；保留未受影响用例的执行结果",
  "materials": [{"type":"text","content":"在这里提供已确认的需求及变更内容"}],
  "repositories": [
    {"projectId":"app-engine","baseRef":"master","targetRef":"feature-branch"}
  ]
}
```

同一调用方的 externalId 对同一内容幂等；不同内容返回 409。需求或代码更新时使用新的 externalId，不覆盖已完成任务。服务端拒绝请求传入 command、workspace、callbackUrl 或仓库 localPath。仓库根目录和回传 URL 只能由部署配置决定。

状态：QUEUED / RUNNING / SUCCEEDED / BLOCKED / FAILED / CANCELLED。成功表示获得并校验了分析产物，不表示测试执行通过。人工导入结果标记为 manual，自动结果标记为 codex。异常退出、缺失执行配置和资料缺口不能当作分析成功。

## 结果契约与增量合并

执行端产物包含 schemaVersion（当前为字符串 1）、requirementKey、sources、findings、apis、cases、reportMarkdown、coverageGaps。每类记录有唯一 id；用例引用的 findingIds、apiIds，以及问题或接口引用的 relatedCaseIds 必须能找到对应记录。详细的生成约束见 scripts/codex-analysis/run.mjs 和随附 skill。

最小导入示例（仅验证协议，不能当作实际分析报告）：

```json
{
  "schemaVersion":"1", "requirementKey":"DEMO-1",
  "sources":[], "findings":[], "apis":[], "cases":[],
  "reportMarkdown":"联调示例，尚未开展业务分析。",
  "coverageGaps":["尚未读取需求和代码，也未执行测试"]
}
```

管理员可对阻塞、失败或排队任务导入结果；不能替换正在执行或已完成的结果。该动作会触发结果回传，应导入正确单号的完整产物。

**本次只实现 mobile 平台的接单、执行和结果出口，尚未实现 TestForge 的任务提交适配、回传接收器与报告合并。** TestForge 需要保存任务 ID，轮询或验签接收通知后拉取结果，再按稳定用例标识和版本合并。expectedReportRevision 用来防止旧分析覆盖新报告，必须由 TestForge 校验；本平台不直接修改 TestForge 报告。未变化的已测用例应保留执行记录，受影响用例标记需复测，新增用例单独标记，不能把所有旧用例一律重置。

## 回传验签

完成后向配置中该调用方的固定 URL 发出 JSON 通知，包含 id、external_id、requirement_key、status、result_hash、result_source、callback_event_id、expectedReportRevision、schemaVersion。接收方收到通知后通过结果接口拉取完整产物。

请求头：X-Analysis-Timestamp（Unix 秒）、X-Analysis-Event（稳定事件 ID）、X-Analysis-Signature（十六进制 HMAC-SHA256）。签名密钥是该调用方的 open-api key，签名内容为以下 UTF-8 原始字符串：

```text
timestamp + "\n" + eventId + "\n" + rawRequestBody
```

接收方使用原始请求体验签、限制时间偏差，并对事件 ID 去重；验签成功且入队持久化后返回 2xx。通知为至少一次投递，可能重复，不保证恰好一次。网络失败会退避重试，默认最多 5 次；页面可以只重试回传，不重新调用模型。未配置 URL/密钥时显示未配置。回传失败不改变已完成的分析结果。密钥、材料和模型日志应按内部敏感数据管理；执行目录只开放给运行账户。

## 已验证与边界

后端编译、13 项隔离单元测试、前端生产构建和 worker 语法检查通过。未启动业务服务、未连接业务数据库、未调用模型、未实际发送回传。首次部署请先以 DEMO 单号和隔离材料验证鉴权、接单、查询、管理员导入及通知，再启用真实分析。模型产物仍需测试人员复核，不保证与另一次会话逐字一致。


## 按单材料契约 v2（本次新增，TestForge 请按此对接）

可直接复制 `analysis-task-v2.example.json`，替换示例内容后提交到原 POST 接口。仍使用原来的 API Key、状态查询和签名通知协议，无需新增表或 ALTER 已有桥接表。新增字段保存于原 request_json，旧任务仍可读取。旧版请求继续兼容，但没有分组/材料版本标识；新集成必须发送 contractVersion="2"。

| 字段 | 必填 | 说明 |
|---|---|---|
| contractVersion | 是 | 字符串 "2"，与结果 schemaVersion="1" 是不同契约 |
| externalId | 是 | 每次新快照一个 ID；网络重试保持同 ID 与完整请求内容 |
| businessGroup | 是 | APP、WEB、PVP，不能仅按 JIRA 前缀猜测 |
| requirementKey | 是 | 规范大写单号；每个任务只属于一个单子 |
| materialVersion | 是 | 调用方生成的材料快照版本，变更后使用新版本 |
| expectedReportRevision | 建议 | TestForge 当前报告版本，接收结果时由 TestForge 做乐观锁校验 |
| previousJobId | 否 | 更新分析时引用本调用方、同业务组、同 JIRA 且成功的旧任务；其他引用拒绝 |
| repositories | 否 | 本单项目列表；每项 projectId/baseRef/targetRef；同项目可以有多条不同提测分支 |
| materials | 否 | 本单材料数组，最多 50 项 |
| changeNotes | 否 | 本次关注点、验收条件和已确认的变更 |

有多个同名项目时使用全局唯一 projectId（例如 app/app-admin、web/app-admin）并在服务端配置对应仓库映射。业务组是任务属性，不用于从其他单子自动推导需求或关联 SQL。

repositories 可选 baseSha、targetSha（完整 40 位 SHA）。建议 TestForge 在确认分支后传入，执行端会验证引用与 SHA 一致，不一致则阻塞、重新确认；不传则以开始执行时解析的引用为准。实际 baseSha/targetSha 会出现在结果 repositorySnapshots 中（不包含本机路径）。重试分支任务时若没有指定 SHA，提交可能已变化，建议用新任务记录新快照。

每份 materials 必须包含 id、type、title，至少有 content 或 url；可带 version、requirementKey 等来源元数据。显式 requirementKey 与任务不同则拒绝。支持 type：requirement、design、schema、migration、sampleData、apiSpec、configuration、changeNote。SQL 结构和迁移应分开；样例不是必填，需脱敏并保持关联关系。链接必须为无账号密码的 HTTP(S) 地址。不能传材料本地路径或命令。当前不提供服务端任意 URL 下载或二进制文档解析接口，链接是否可读由执行端工具决定，读取失败需报告缺口。

前端支持粘贴正文或读取 SQL/TXT/MD/JSON/CSV/HTML/XML 文本文件。Word、Excel、图片目前需要调用方提取正文/设计说明或提供可访问来源链接；不要把二进制或 base64 粘到 content。单份正文不超过 500000 字符，整个请求不超过 2000000 字符（前端同时按 2MB UTF-8 做更保守限制）。不传真实数据库密码，数据库结构分析不代表已连接数据库或完成执行。

服务端不接收 previousResult、analysisContext 或 resolvedRepositories；历史结果只能通过经过归属校验的 previousJobId 注入。每次执行新建 Codex 会话，按指定项目分析同单材料，不复用其他任务的会话。此处是应用层归属校验和模型读取约束，并不是不同任务间的操作系统级文件隔离；如有不同信任域，应分别部署执行账户/容器及仓库权限。

结果新增 analysisContext（服务端覆盖生成）：jobId、client、requirementKey、businessGroup、materialVersion、requestHash、previousJobId、expectedReportRevision，以及 materialManifest（材料 ID、类型、来源元数据与内容哈希）。查询详情新增 analysisInput，供测试核对本次完整材料；列表新增 businessGroup/materialVersion，不返回材料正文。回传通知新增 businessGroup、materialVersion、requestHash；result_hash 对最终完整产物计算。接收方必须核对任务 ID、单号、业务组和报告版本后再合并，不能仅凭单号覆盖。

更新分析会生成新产物，不改写历史任务。执行端要求保留稳定用例 ID、说明新增/调整/复测原因，但实际用例执行状态的保留、版本冲突处理和报告合并仍由 TestForge 负责。当前没有跨系统直接执行接口、写库造数或自动重置执行记录。


## JIRA 自动材料读取入口

新增两阶段读取与确认流程，详见 [JIRA_ANALYSIS_INGEST.md](JIRA_ANALYSIS_INGEST.md)。支持 JIRA 来源识别、Wiki 快照、Figma 指定节点及图文输入；旧的仅文字 SDK 输入说明以该新增文档为准。此次不需要新增或修改数据库表字段。


## 2026-10-27 结果同步补充

创建、列表、详情新增 resultReady、terminal、statusUrl、resultUrl、pollAfterSeconds；结果可获取与通知是否成功相互独立。支持调用方 POST /api/open/v1/analysis-jobs/{id}/retry-callback，按任务归属校验，只补发通知。配置回调后，会自动恢复此前NOT_CONFIGURED的已完成通知，FAILED仍须主动重试。通知新增resultReady、resultUrl。

部署通过 TESTFORGE_ANALYSIS_CALLBACK_URL 设置固定地址。本机配置为 http://127.0.0.1:3800/api/analysis-bridge/callback。完整接入步骤及PVP-13859现有任务见 [TESTFORGE_DELIVERY_HANDOFF.md](TESTFORGE_DELIVERY_HANDOFF.md)。无需新增或修改表结构。
