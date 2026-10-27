# 从 JIRA 读取本单材料并分析

入口：主菜单 → 分析任务 → 创建分析任务。输入 JIRA 单号或链接，点击「从 JIRA 读取」。平台后台读取，页面自动刷新状态；完成后核对项目、比较基线、提测分支、材料和获取缺口，再点「提交分析」。此时才调用已启用的 Codex 执行端。读取材料本身不调用模型，不改动 JIRA 状态或评论。

## 配置

合并到已有配置，不要覆盖现有发版模块的账号、仓库和调用方配置：

```yaml
release-doc:
  jira-base-url: https://jira.shdev.net
  wiki-base-url: https://wiki.shdev.net
  username: ${COMPANY_DOC_USERNAME:}
  password: ${COMPANY_DOC_PASSWORD:}
analysis-bridge:
  enabled: false # 确认执行环境后改 true
  figma-token: ${FIGMA_ACCESS_TOKEN:}
  repositories:
    pvp-client: /absolute/server/repos/pvp-client
    app-engine: /absolute/server/repos/app-engine
  repository-groups:
    pvp-client: PVP
    app-engine: APP
  repository-aliases:
    "PVP:client": pvp-client
    "APP:engine": app-engine
  repository-baselines:
    pvp-client: confirmed-release-base
    app-engine: confirmed-release-base
  # repository-urls:
  #   pvp-client: git@gitlab.shdev.net:YOUR_NAMESPACE/pvp-client.git
```

本版复用 release-doc 的公司文档读取账号。JIRA 新客户端遵循 Java 默认 TLS 信任，需要安装公司 CA / 配置 JVM 信任库，不能靠浏览器已登录来授权后台。不同系统账号不同时需后续拆分独立认证配置。Wiki 复用已有读取模块；下载只允许配置的 Wiki 同源地址，不把账号发往外部图片站点，不自动跟随重定向。Figma Token 只用于 api.figma.com 请求，下载设计图不携带 Token。

项目 ID 必须唯一。别名支持「组:别名」避免同名项目串组；repository-groups 可设 APP、WEB、PVP 或 SHARED。未配置分组的旧项目维持兼容，请正式使用前补齐分组。只从本单描述、相关自定义字段和评论里识别明确的项目与分支，不自动加入所有配置仓库。结果只是候选，含旧评论的冲突分支需测试手动移除；不能将识别结果当作已经确认的提测范围。比较基线不默认 master，缺失时由测试补充。

仓库同步仅使用管理员配置的目录/clone URL，采用已有 Git/SSH 凭据；缺少目录且提供 clone URL 才尝试 clone。不会 checkout、reset 或强制移动开发分支；fetch 更新 refs/qa-analysis 下的专用引用。同步失败会明确记录使用本地版本的缺口，最终报告记录实际 SHA。分支名与预期 SHA 不一致时停止发布有效分析结果。Git 操作超时有界，但失败 clone 的隔离临时目录可能留下，清理前请核对用途。

## 材料获取范围

- JIRA：本单描述、相关自定义字段、分页评论和附件清单；SQL/TXT 附件按 500KB 限制读取。Word、Excel、PDF 等 JIRA 二进制附件当前保留清单并报告未解析，不假装已读。
- Wiki：从本单材料识别带 pageId 的链接，获取 storage/view、版本、评论、附件，保留章节表格和代码块、图片位置与重复引用；排除附件占位图。正文折叠区随源结构读取，DEMO 子章节保持参考方案标识。评论或附件接口失败不会丢弃已读取正文。
- Wiki 导出兜底：识别 MIME、quoted-printable 正文、octet-stream PNG/JPEG，通过嵌入位置映射图片。兜底缺失的评论、附件及版本信息列为覆盖缺口。未授权图片、外部图片、非 PNG/JPEG 资源不声称已获取。
- Figma：通过官方 API 读取明确 node-id 的节点、文字和 PNG 静态设计图，保存原始节点 JSON。无 Token、缺少节点或权限失败时显示获取失败/部分获取。没有自动遍历全文件、Figma 评论和所有原型跳转。
- 单次准备最多读取 12 个关联 Wiki/Figma 来源；不递归无限抓取关联页面。子页面、其他被引用文档需要手动加入并另行确认获取范围。

材料按 preparation UUID 分目录存储在 analysis-bridge.workspace/preparations 下，目录仅运行账号可读。页面 READY 表示材料准备操作已结束，不表示所有来源都获取成功；逐项查看 ready/partial/failed 与 gaps。进程重启时未完成的 PREPARING 不自动恢复，请重新读取。当前无需新增数据库表；大材料保存在文件目录，请持久化该目录并纳入内部数据保留策略。

## TestForge 调用

仍用原 X-API-Key。先请求：

```http
POST /api/open/v1/analysis-jobs/prepare
Content-Type: application/json
X-API-Key: <configured-key>

{"requirementKey":"PVP-13859","businessGroup":"PVP"}
```

已知 APP/WEB/PVP 前缀时 businessGroup 可省略，其他前缀必须传组。返回准备 id/status=PREPARING，不是分析任务 ID。随后：

```http
GET /api/open/v1/analysis-jobs/preparations/{preparationId}
```

READY 返回 draft（本单材料和项目候选）、sources（来源获取状态）、discoveryGaps。FAILED 返回 error。测试确认/修正 draft 后，通过原 POST /api/open/v1/analysis-jobs 提交：

```json
{
  "contractVersion":"2",
  "externalId":"PVP-13859-analysis-001",
  "businessGroup":"PVP",
  "requirementKey":"PVP-13859",
  "materialVersion":"原样使用准备结果 draft.materialVersion",
  "preparationId":"准备 id",
  "repositories":[{"projectId":"pvp-client","baseRef":"已确认基线","targetRef":"已确认提测分支"}],
  "materials":["替换成 draft.materials 中确认保留的材料对象"],
  "expectedReportRevision":"TestForge 当前报告版本"
}
```

materials 上面仅示意位置，实际必须为材料对象数组，完整字段参考原 v2 契约。preparationId 只能属于当前调用方、同一业务组/单号/材料版本；不接受外部注入快照文件路径。移除某份材料的 URL 后，该来源快照不会进入执行输入；新增 URL 需要重新读取，不能冒用旧准备结果声称已采集。仅保留准备结果不提交分析不会调用模型。

当前是两阶段确认协议，不是只 POST 单号就直接产生完整分析；这样可以在模型调用前核对识别错误和缺口。现有不带 preparationId 的创建接口仍兼容，仅沿用原 Wiki 自动读取能力，不能声称已执行新的 JIRA/Figma 获取流程。

## 分析输入和结果

执行脚本读取本任务允许的快照文件，并把已准备的 PNG/JPEG 通过 SDK local_image 输入。仅当前任务/准备目录下的文件被接纳；按内容去重，最多 40 张、合计 80MB，超过部分强制进入 coverageGaps。长正文在任务 JSON 中分段读取，不以摘要替代全量正文。材料已获取不等于模型已完整理解，最终仍需看 sources、证据及覆盖缺口。

结果增加 materialCapture、imageInputCount，并保留 materialGaps/discoveryGaps 到 coverageGaps。结果回传、鉴权、报告归属和版本合并仍按 ANALYSIS_BRIDGE.md。接口执行、业务数据库写入及测试结果回填不在本次自动执行范围内。

## 验证状态

使用合成 JIRA 分页响应、MIME 图片、跨任务引用及 Wiki 结构样本运行单元测试；未调用真实模型，未写业务数据库。真实 JIRA/Wiki/Figma 和 GitLab 的权限、证书及网络需拿一条单子联调确认。
