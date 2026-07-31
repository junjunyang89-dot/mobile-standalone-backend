# 移动端自动化测试平台 - 最新接口文档

**基址说明：** 后端 `context-path` 为 `/api`，默认端口 `3000`。  
**baseUrl 示例：** `http://localhost:3000/api`（实际部署时替换为对应域名与端口，路径必须包含 `/api`）。

---

## 一、接口总览

| 模块 | 前缀 | 说明 |
|------|------|------|
| 设备 | `/devices` | 设备列表、首页统计、设备详情、扫描、连接/断开 |
| 功能测试 | `/functional-test` | 场景列表、一键执行、测试任务列表与详情、停止/删除 |
| 应用 | `/applications` | 应用列表、按设备/平台查应用、上传、安装到设备 |
| 任务 | `/tasks` | 通用任务列表、创建、运行、停止、删除（与 functional-test 任务互补） |
| 健康检查 | `/health` | 健康检查、版本 |

---

## 二、设备接口（/api/devices）

### 2.1 首页 - 已连接设备统计（推荐首屏调用）

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/devices/connected-summary` |

**响应 200：**

```json
{
  "totalConnected": 2,
  "androidCount": 1,
  "iosCount": 1,
  "harmonyosCount": 0,
  "devices": [
    { "id": "xxx", "name": "SEA_AL10", "platform": "ANDROID", "version": "10", "status": "CONNECTED", "lastSeen": "..." },
    { "id": "yyy", "name": "iPhone", "platform": "IOS", "version": "17.0", "status": "CONNECTED", "lastSeen": "..." }
  ]
}
```

- **totalConnected**：已连接设备总数（首页卡片）。
- **devices**：已连接设备列表（设备状态表格数据源）。

---

### 2.2 设备详情（点击某台设备后调用）

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/devices/{deviceId}/detail` |

**路径参数：** `deviceId` 为设备 ID（与 connected-summary 或设备列表中的 `id` 一致）。

**响应 200 主要字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id, name, platform, version, status, lastSeen | - | 基础信息 |
| batteryLevel | number \| null | 电量 0–100 |
| installedApplications | array | 已安装应用列表，每项：name, packageName, version |
| iosDetail | object | **仅 iOS**：ideviceinfo 解析键值对 |
| iosDetailRaw | string | **仅 iOS**：ideviceinfo 完整原始输出，前端用 `<pre>` 完整展示 |
| androidDetail | object | **仅 Android/HarmonyOS**：systemInfo, hardwareInfo, batteryInfo, storageInfo, networkInfo, securityInfo, sensorInfo |
| androidDetailRaw | string | **仅 Android/HarmonyOS**：getprop + dumpsys 完整原始输出，前端用 `<pre>` 完整展示 |

**前端务必展示 `iosDetailRaw` 或 `androidDetailRaw`**，有数据时为完整设备信息，失败时为后端返回的错误提示文案。

---

### 2.3 设备列表

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/devices` |

**响应 200：** JSON 数组，每项设备含 `id`, `name`, `platform`, `version`, `status`, `lastSeen`, `config`。

---

### 2.4 其他设备接口

| 方法 | URL | 说明 |
|------|-----|------|
| GET | `{baseUrl}/devices/{id}` | 获取单个设备（基础信息） |
| POST | `{baseUrl}/devices/scan` | 手动触发设备扫描 |
| GET | `{baseUrl}/devices/platform/{platform}` | 按平台筛选，platform：ANDROID / IOS / HARMONYOS |
| POST | `{baseUrl}/devices/{id}/connect` | 连接设备（更新状态） |
| POST | `{baseUrl}/devices/{id}/disconnect` | 断开设备（更新状态） |

---

## 三、功能测试接口（/api/functional-test）

### 3.1 可执行场景列表

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/scenarios` |

**响应 200：** 场景数组，每项含 `id`, `name`, `platform`, `taskType`, `description`, **`scenarioKey`**（功能基础质量测试下为 `launch_app` / `login` / `search`，其他类型可为 null）。  
常用 scenarioId：`launch_app_android` / `launch_app_ios` / `launch_app_harmonyos`、`login_android` / `login_ios`、`search_android` / `search_ios`、`compatibility_android` / `compatibility_ios` 等。

**仅功能基础质量测试场景：** `GET {baseUrl}/functional-test/scenarios/functional-quality`，返回仅含「启动应用程序」「登录」「搜索」的场景，供创建任务时「测试场景」下拉用。

**按平台分组（供折叠展示）：** `GET {baseUrl}/functional-test/scenarios/by-platform`，返回按 Android、iOS、HarmonyOS 分组的场景列表，便于前端折叠展示。

**响应 200 示例：**
```json
[
  { "platform": "ANDROID", "platformLabel": "Android", "scenarios": [{ "id": "launch_app_android", "name": "启动应用程序(Android)", "platform": "ANDROID", "taskType": "FUNCTIONAL_QUALITY_TEST", "description": "Android 应用启动检测", "scenarioKey": "launch_app" }, ...] },
  { "platform": "IOS", "platformLabel": "iOS", "scenarios": [...] },
  { "platform": "HARMONYOS", "platformLabel": "HarmonyOS", "scenarios": [...] }
]
```

---

### 3.2 一键执行（创建任务并立即执行）

| 项目 | 值 |
|------|-----|
| 方法 | `POST` |
| URL | `{baseUrl}/functional-test/execute` |
| Content-Type | `application/json` |

**请求体示例：**

```json
{
  "scenarioId": "login_android",
  "deviceId": "设备ID",
  "applicationId": "应用ID",
  "config": "{\"phoneNumber\":\"12312340002\",\"verificationCode\":\"521758\"}"
}
```

- **scenarioId**：必填，如 login_android、compatibility_android、install_upgrade_android、ui_adapt_android 等。
- **deviceId / applicationId**：必填。
- **config**：可选；登录测试时推荐传 JSON 字符串，含 phoneNumber、verificationCode。

**响应 200：** `{ "taskId": "xxx", "status": "RUNNING", "message": "任务已提交执行" }`。前端用 taskId 轮询任务状态。

---

### 3.3 测试任务列表（分页）

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks` |

**查询参数（均可选）：**  
- `status`：任务状态  
- `keyword`：任务名或备注关键词  
- `days`：3=近三天，7=近一周，不传=全部  
- `page`：页码，从 1 开始，默认 1  
- `size`：每页条数，默认 10，最大 100  

**响应 200：** 分页对象，结构如下。

| 字段 | 类型 | 说明 |
|------|------|------|
| content | 数组 | 当前页任务列表，每项含 `id`, `taskName`, `remarks`, `platform`, `deviceId`, `applicationId`, `deviceName`, `status`, `taskType`, **`scenarioKey`**（launch_app/login/search）, **`scenarioName`**（如「启动应用程序(Android)」）, `createdAt` 等 |
| totalElements | number | 总记录数（符合筛选条件的总数） |
| totalPages | number | 总页数 |
| page | number | 当前页码 |
| size | number | 每页条数 |

包含功能测试、兼容性测试、安装卸载升级测试、界面与适配测试等类型。前端可根据 `totalPages`、`page` 渲染分页控件。

---

### 3.4 任务详情

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}` |

**响应 200：** 任务详情，含 `executionStatus`, `reportPath`, `errorMessage`, `resultData`, `executionEndTime`, **executionLog**（执行步骤日志）, **executionVariables**（执行变量脱敏）, **scenarioKey**（launch_app/login/search）, **scenarioName**（场景展示名）等。详见《任务详情-手机号验证码对接说明.md》。创建任务与场景字段说明见《功能基础质量测试-场景与前端对接.md》。

---

### 3.4.1 任务详情概览（任务详情页）

用于前端「任务详情」页与截图一致：蓝色信息卡（任务 ID/标题/备注、测试类型、应用包名/版本、API 版本、开始时间、持续时间、执行人、报告路径）、概览 Tab（兼容性满足度 + 测试结果分布）、检测规则 Tab（表格 + 详细日志）。

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}/overview` |

**路径参数：** `id` 为任务 ID。

**支持任务类型：** 功能体验基础质量测试（FUNCTIONAL_QUALITY_TEST）、兼容性测试（COMPATIBILITY_TEST）、性能基础质量测试（PERFORMANCE_BASELINE_TEST）；其他类型返回 404。

**响应 200 示例（功能基础质量测试）：**

```json
{
  "taskId": "task-uuid-xxx",
  "title": "功能体验基础质量测试",
  "remarks": "可选备注",
  "testType": "功能体验基础质量测试",
  "appPackageName": "com.pwrd.steam.esports.hos",
  "appVersion": "3.7.7.187",
  "apiVersion": null,
  "startTime": "2026-01-13T16:07:46",
  "duration": "5分50秒",
  "executor": "杨俊俊",
  "reportPath": "/tmp/screenshots/login_xxx.png",
  "summarySectionTitle": "功能质量达标率",
  "compatibilitySummary": {
    "actualTestItemCount": 3,
    "passedCount": 3,
    "failedCount": 0,
    "warningCount": 0
  },
  "resultDistribution": [
    { "categoryName": "登录", "passed": 3, "warning": 0, "failed": 0, "deviceDependent": 0 }
  ],
  "testResultItems": [
    { "testItemCategory": "登录", "testItemName": "输入手机号", "testResult": "通过", "detailLogLink": null },
    { "testItemCategory": "登录", "testItemName": "输入验证码", "testResult": "通过", "detailLogLink": null },
    { "testItemCategory": "登录", "testItemName": "完成登录", "testResult": "通过", "detailLogLink": null }
  ]
}
```

**响应 200 示例（兼容性测试）：** `testType` 为「应用」；`testResultItems` 含「OS兼容」2 条（应用/元服务启动、运行无兼容性问题）、「设备兼容」3 条（折叠屏状态/横竖屏/支架态切换），结果可为「通过」「设备依赖」等。`resultDistribution` 按「OS兼容」「设备兼容」给出 passed/warning/failed/deviceDependent 数量，便于画柱状图。

**字段说明：**

- **taskId**：任务 ID，可用于展示或「Task-xxx」格式化。
- **title / remarks**：任务标题、备注（蓝色卡左侧）。
- **testType**：测试类型（兼容性任务为「应用」，功能质量为「功能体验基础质量测试」）。
- **appPackageName / appVersion / apiVersion**：应用信息（无则 null）。
- **startTime / duration / executor**：开始时间、持续时间、执行人（固定「杨俊俊」）。
- **reportPath**：报告/截图在服务端的路径（仅作展示或排查用）。**前端「查看报告」或展示截图时，必须使用截图接口 URL**（见下方「获取任务执行截图」），**不要**用 reportPath 或文件名作为请求地址，否则会 404。
- **summarySectionTitle**：概览中统计区块的标题，按实际测试类型返回，如功能基础质量测试为「功能质量达标率」、兼容性测试为「兼容性满足度」。前端用此字段展示该区块标题（红圈处）。
- **compatibilitySummary**：概览-统计区块数据（实际测试项、通过项、不通过项、告警项），用于环形图或文案。
- **resultDistribution**：概览-测试结果分布，按分类的 passed/warning/failed/deviceDependent，用于柱状图。
- **testResultItems**：检测规则表格；每项含 testItemCategory、testItemName、testResult、detailLogLink、**detailMessage**（详细日志列展示文案；功能质量测试时「输入手机号」「输入验证码」行为脱敏值，如 `138****8000`、`****(6位)`，其余为 null）。
- **executionVariables**：执行变量脱敏对象，键 `phoneNumberMasked`、`verificationCodeMasked`，值如 `"138****8000"`、`"****(6位)"`，供前端在检测规则或其它处展示；未执行或非登录测试时为 null。

**响应 404：** 任务不存在或任务类型不支持。

---

### 3.4.2 执行日志（任务详情页「执行日志」）

功能体验基础质量测试（如登录测试）会按步骤记录执行日志，失败时也会保留已执行到的步骤及**未定位到的元素**（selector），便于排查。

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}/execution-log` |

**响应 200：** JSON 数组，每项一条步骤，按时间顺序。前端可展示为「时间 + Level + 消息」，与常见执行日志面板一致。

| 字段 | 说明 |
|------|------|
| step | 步骤名称，如「开始测试」「获取执行步骤」「应用包名」「启动应用程序」「输入手机号」「报告已生成,任务完成」 |
| level | 日志级别：`Info` / `Error`，便于筛选与展示 |
| status | `success` / `fail` |
| message | 说明信息（可含脱敏变量，如 应用包名:com.xxx、应用版本:3.7.9、已输入:138****8000、已输入:****(6位)、设备 23E0****0020 设备,环境初始化执行成功） |
| selector | 失败时未定位到的元素定位符，成功时为 null |
| timestamp | 时间（ISO） |

**登录测试日志流程示例：** 开始测试 → 获取执行步骤 → 正在解析应用信息 → 应用包名/应用版本 → 应用信息解析完成 → 正在检查设备状态并执行环境初始化 → 设备环境就绪 → 启动应用程序 → … → 执行完成并截图 → 正在汇总数据并生成报告 → 报告已生成,任务完成 → 测试服务执行完毕。

**说明：** 任务详情 `GET /tasks/{id}` 的响应里也包含 **executionLog**、**executionVariables**（手机号/验证码脱敏值，见《任务详情-手机号验证码对接说明.md》），可直接用其展示执行日志与变量，无需单独请求本接口。

---

### 3.4.3 截图（任务详情页展示图片）

执行完成后会保存截图，前端通过本接口获取图片二进制流并展示（如 `<img src="...">`）。

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}/screenshot` |
| Accept | 无需特殊头，返回 `image/png` |

**响应 200：** 图片二进制，`Content-Type: image/png`。前端可直接用该 URL 作为图片地址，例如：`<img src="{baseUrl}/functional-test/tasks/{taskId}/screenshot" alt="执行截图" />`。

**响应 404：** 任务无最近一次执行、或执行无 reportPath、或文件不存在（如截图目录不可用）。

**安全：** 仅允许访问 `/tmp/screenshots` 下的截图文件，避免路径穿越。

---

### 3.4.3.1 步骤截图（功能体验测试每步一张）

功能体验基础质量测试（如登录测试）会在关键步骤自动截屏，前端可在执行日志中为每一步展示对应截图。

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}/screenshots/{stepIndex}` |
| 路径参数 | `stepIndex`：步骤下标，与 `executionLog` 数组下标一致（从 0 开始） |

**响应 200：** 该步骤的截图二进制，`Content-Type: image/png`。

**响应 404：** 该步骤无截图、或文件不存在。

**前端用法：** 任务详情或执行日志接口返回的 `executionLog` 中，每条可能带 `screenshotUrl`（如 `functional-test/tasks/{id}/screenshots/0`）。前端用 `baseUrl + "/" + entry.screenshotUrl` 作为 `<img src="...">` 即可展示该步截图；无 `screenshotUrl` 的步骤不展示图片。

**性能说明：** 每步截图约增加 0.3～1 秒，整次任务约多几秒到十几秒，属可接受范围；前端可全部展示，按需懒加载图片即可。

---

### 3.4.4 「测试报告」如何展示、用哪个接口

任务详情页里的「测试报告」可以理解为：**本次任务执行的结果汇总**，由以下几部分拼起来即可。

| 展示内容 | 数据来源 | 接口/字段 |
|----------|----------|-----------|
| 报告整体结构（标题、测试类型、应用信息、执行信息、达标率标题、统计数字、检测规则表） | 任务详情概览 | **GET** `{baseUrl}/functional-test/tasks/{id}/overview` |
| 达标率/统计（实际测试项、通过/不通过/告警、环形图、柱状图） | 同上 | 同上响应的 `summarySectionTitle`、`compatibilitySummary`、`resultDistribution` |
| 检测规则表格（测项分类、测试项名称、测试结果） | 同上 | 同上响应的 `testResultItems` |
| 执行日志（步骤列表） | 任务详情 或 执行日志接口 | **GET** `{baseUrl}/functional-test/tasks/{id}` 的 `executionLog`，或 **GET** `{baseUrl}/functional-test/tasks/{id}/execution-log`；每条可能含 **screenshotUrl**，用于请求该步截图 |
| 每步截图 | 步骤截图接口 | **GET** `{baseUrl}/functional-test/tasks/{id}/screenshots/{stepIndex}`，stepIndex 与 executionLog 下标一致 |
| 执行结果截图（最终） | 截图接口 | **GET** `{baseUrl}/functional-test/tasks/{id}/screenshot`（作为 `<img src="...">` 的地址） |

**推荐用法：**

1. **先请求一次概览**：`GET /api/functional-test/tasks/{id}/overview`  
   → 用返回数据渲染：头部信息卡、概览 Tab（达标率 + 测试结果分布）、检测规则 Tab。

2. **「测试报告」Tab 或区块**可以包含：  
   - 执行日志列表：再请求 `GET /api/functional-test/tasks/{id}`，用响应里的 **executionLog** 渲染（或单独请求 `.../execution-log`）。  
   - 每步截图：若 `executionLog[i].screenshotUrl` 存在，用 **`<img src="{baseUrl}/{screenshotUrl}" />`** 展示该步截图（可懒加载）。  
   - 执行结果截图（最终）：用 **`<img src="{baseUrl}/functional-test/tasks/{id}/screenshot" />`** 展示。

3. 若希望少请求：**GET /tasks/{id}** 已包含 `executionLog`、`reportPath` 等，可和 overview 一起用；截图仍需用截图接口的 URL 作为图片地址（不能直接用 reportPath）。

**总结：**  
- 报告结构和数字、检测规则 → 用 **overview** 接口。  
- 执行日志 → 用 **任务详情** 的 `executionLog` 或 **execution-log** 接口。  
- 报告里的截图 → 用 **screenshot** 接口的 URL 展示图片。

---

### 3.4.5 性能基础质量测试 - 性能报告（仅性能任务）

任务类型为「性能基础质量测试」时，详情页「性能报告」区块需通过**接口**获取数据，**不要**把该接口路径当作静态资源（如 iframe src），否则会 404。

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/functional-test/tasks/{id}/performance-report` |

**路径参数：** `id` 为任务 ID。

**响应 200：** 最新一次执行的性能结果 JSON 对象（即 `result_data` 解析后），包含：`traversalDurationMinutes`、`totalOperations`、`launchCount`、`clickCount`、`swipeCount`、`conclusion`、`resourceSummary`（CPU/内存 peak/min/avg）、`issueSummary`、`resourceSamples`、`operations` 等。前端用此数据渲染「性能报告」的表格与曲线。

**响应 404：** 任务不存在、非性能任务、或尚无执行结果 / 无 result_data。

---

### 3.4.6 性能测试 - 创建任务时传执行时长

创建性能测试任务时，可在 **config** 中传入执行时长（分钟），供后端按该时长做遍历。

- **接口**：`POST {baseUrl}/tasks/test`（见「任务」相关章节）。
- **请求体**：在 `config` 字段传 JSON 字符串，例如：`"config": "{\"traversalMinutes\": 5}"` 表示执行时长为 5 分钟。
- **范围**：1～60 分钟；未传或无效时使用服务端默认（如 2 分钟）。
- **前端建议**：在创建「性能基础质量测试」任务时，增加「执行时长」选择（如 2/5/10/30/60 分钟），将选中值写入 `config.traversalMinutes` 后提交。

---

### 3.5 停止 / 删除任务

| 方法 | URL |
|------|-----|
| POST | `{baseUrl}/functional-test/tasks/{id}/stop` |
| DELETE | `{baseUrl}/functional-test/tasks/{id}` |

---

## 四、应用接口（/api/applications）

| 方法 | URL | 说明 |
|------|-----|------|
| GET | `{baseUrl}/applications` | 应用列表 |
| GET | `{baseUrl}/applications/{id}` | 应用详情 |
| GET | `{baseUrl}/applications/platform/{platform}` | 按平台筛选 |
| GET | `{baseUrl}/applications/devices/{deviceId}/applications` | 某设备下的应用列表（执行测试时选应用用此接口取 applicationId）。**可选 query：`refresh=true`** 强制从设备重新扫描，可拿到新安装的应用（否则用数据库缓存，新装应用可能不出现）。 |
| POST | `{baseUrl}/applications` | 创建应用 |
| PUT | `{baseUrl}/applications/{id}` | 更新应用 |
| DELETE | `{baseUrl}/applications/{id}` | 删除应用 |
| POST | `{baseUrl}/applications/upload` | 上传应用 |
| POST | `{baseUrl}/applications/{id}/install` | 安装到设备（query: deviceId） |

---

## 五、健康检查

| 方法 | URL | 说明 |
|------|-----|------|
| GET | `{baseUrl}/health` | 健康检查 |
| GET | `{baseUrl}/health/version` | 版本信息 |
| GET | `{baseUrl}/health/appium` | Appium 配置与连通性排查（appium.enabled、Server 是否可达、截图驱动） |

---

## 六、其他模块（简要）

### 6.1 通用任务列表（分页）— 测试任务页

| 项目 | 值 |
|------|-----|
| 方法 | `GET` |
| URL | `{baseUrl}/tasks` |

**Query 参数（均可选）：**

| 参数 | 类型 | 说明 |
|------|------|------|
| taskType | string | 任务类型枚举，如 FUNCTIONAL_QUALITY_TEST、COMPATIBILITY_TEST |
| platform | string | ANDROID / IOS / HARMONYOS |
| status | string | PENDING / RUNNING / COMPLETED / FAILED / STOPPED |
| deviceId | string | 设备 ID |
| appId | string | 应用 ID |
| **page** | number | 页码，从 1 开始；**与 size 同时传入时返回分页结构** |
| **size** | number | 每页条数，默认 10，最大 100 |

**分页请求示例：** `GET {baseUrl}/tasks?page=1&size=10`  
**带筛选：** `GET {baseUrl}/tasks?taskType=FUNCTIONAL_QUALITY_TEST&platform=ANDROID&page=1&size=10`

**响应（传了 page 与 size 时）：** 200 返回分页对象

| 字段 | 类型 | 说明 |
|------|------|------|
| content | array | 当前页任务列表 |
| totalElements | number | 总记录数 |
| totalPages | number | 总页数 |
| page | number | 当前页码 |
| size | number | 每页条数 |

**响应（未传 page/size 时）：** 200 直接返回任务数组（兼容旧版）。

**前端建议：** 测试任务列表页使用 `?page=1&size=10` 并展示分页组件（总页数、上一页/下一页、每页条数选择）。

### 6.2 设置自动化测试（直调接口，与 automation-service-standalone 对齐）

与旧项目 `AndroidSettingsTestController` / `IOSSettingsTestController` 用法一致：直接 POST 即执行设置测试（先登录再进入设置页并截图），同步返回结果。

| 项目 | 值 |
|------|-----|
| 方法 | `POST` |
| Android | `{baseUrl}/android-settings-test/execute` |
| iOS | `{baseUrl}/ios-settings-test/execute` |

**请求体（JSON）：**

| 参数 | 必填 | 说明 |
|------|------|------|
| deviceId | 是 | 设备 ID |
| applicationId | 否 | 应用 ID（与 packageName/bundleId 二选一，优先使用） |
| packageName | 否 | Android 包名（如 com.pwrd.steam.esports），与 applicationId 二选一 |
| bundleId | 否 | iOS Bundle ID，与 applicationId 二选一 |
| appiumServerUrl | 否 | Appium 服务地址，不传则用配置里的默认值 |

**响应 200：** `{ "code": 200, "message": "Android/iOS设置测试执行成功", "data": { "taskId", "deviceId", "testCaseName", "testSteps": [ { "stepName", "status": "PASSED"/"FAILED", "errorMessage" } ], "reportPath" } }`

**健康检查：** `GET {baseUrl}/android-settings-test/health`、`GET {baseUrl}/ios-settings-test/health`

**说明：** 仅支持完美世界电竞应用（包名含 steam.esports），且需 `appium.enabled=true`。

---

- **任务（通用）**：`/api/tasks` — GET 列表（**支持分页** page、size）、GET/{id}、POST 创建、PUT/{id}、DELETE/{id}、POST/{id}/run、POST/{id}/stop 等。
- **测试用例**：`/api/test-cases` — 用例 CRUD、按平台/应用查询。
- **测试计划**：`/api/test-plans` — 计划 CRUD、按设备/应用查询、启用/触发/暂停/恢复。
- **页面对象**：`/api/page-objects` — 页面对象 CRUD、按页面查询、导入。
- **应用（apps）**：`/api/apps` — GET devices/{deviceId}/apps（已安装应用）、POST upload。
- **报告**：`/api/reports` — 报告列表与详情。

---

## 七、baseUrl 与排查

- **baseUrl** 必须带 `/api`，例如：`http://localhost:3000/api`。
- **设备列表为空**：确认设备 USB 连接、Android 开启 USB 调试 / iOS 信任电脑，可调 `POST /devices/scan` 后重新 `GET /devices`。
- **设备详情无数据**：前端务必渲染 `iosDetailRaw` 或 `androidDetailRaw`；若为错误提示，可按文案排查（如未安装 libimobiledevice/adb、设备未连接等）。

---

**文档版本：** 与当前后端代码一致，覆盖设备、功能测试、应用、任务等主要接口。更细的请求/响应示例见：`设备管理接口-给前端.md`、`执行登录测试接口-给前端.md`、`测试任务类型与前端对接-安装卸载与界面适配.md`。
