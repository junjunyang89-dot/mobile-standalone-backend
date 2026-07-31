# 登录测试相关接口说明

后端默认：`server.port=3000`，`context-path=/api`，因此接口基址为：**`http://localhost:3000/api`**。

---

## 运行登录测试 - 接口速查（前端对照用）

| 用途 | 方法 | 完整 URL | 说明 |
|------|------|----------|------|
| **执行登录测试** | **POST** | **`http://localhost:3000/api/functional-test/execute`** | 请求体见下 |
| 获取场景列表 | GET | `http://localhost:3000/api/functional-test/scenarios` | 拿到 scenarioId（如 login_android） |
| 查任务状态 | GET | `http://localhost:3000/api/tasks/{taskId}` | 执行接口返回的 taskId |

**执行登录测试 - 请求体（Content-Type: application/json）：**

```json
{
  "scenarioId": "login_android",
  "deviceId": "你的设备ID",
  "applicationId": "你的应用ID"
}
```

- **scenarioId**：固定为 `login_android`（Android）或 `login_ios`（iOS），必填。
- **deviceId**：来自设备列表接口，必填。
- **applicationId**：来自「该设备下应用列表」接口里每个应用的 **id**（不是设备ID），必填。
- **config**（可选）：JSON 字符串，例如 `"{\"phoneNumber\":\"13800138000\",\"verificationCode\":\"666888\"}"`。

**前端常见错误：**
1. 把 **applicationId** 传成设备 ID 或空 → 必须传「应用」的 id（来自 `GET /api/applications/devices/{deviceId}/applications` 的某项 `id`）。
2. URL 漏掉 context-path → 必须是 `.../api/functional-test/execute`，不是 `.../functional-test/execute`。
3. 字段名写错 → 必须是 `scenarioId`、`deviceId`、`applicationId`（驼峰，且是 application**Id**）。

---

## 1. 获取可执行场景列表（自动化测试列表）

用于展示「可执行场景」Tab 的列表（含登录测试等）。

| 项目 | 说明 |
|------|------|
| **方法** | `GET` |
| **URL** | `http://localhost:3000/api/functional-test/scenarios` |
| **请求参数** | 无 |
| **响应** | 200，JSON 数组 |

**响应示例：**
```json
[
  {
    "id": "login_android",
    "name": "登录测试(Android)",
    "platform": "ANDROID",
    "taskType": "FUNCTIONAL_QUALITY_TEST",
    "description": "完美世界电竞 Android 登录流程"
  },
  {
    "id": "login_ios",
    "name": "登录测试(iOS)",
    "platform": "IOS",
    "taskType": "FUNCTIONAL_QUALITY_TEST",
    "description": "完美世界电竞 iOS 登录流程"
  }
]
```

**curl 示例：**
```bash
curl -s "http://localhost:3000/api/functional-test/scenarios"
```

---

## 2. 一键执行登录测试（推荐）

选好「场景 + 设备 + 应用」后，调用此接口会**创建任务并立即执行**（异步），返回任务 ID，可用于轮询状态。

| 项目 | 说明 |
|------|------|
| **方法** | `POST` |
| **URL** | `http://localhost:3000/api/functional-test/execute` |
| **Content-Type** | `application/json` |
| **请求体** | 见下表 |

**请求体字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| scenarioId | string | 是 | 场景 ID：`login_android` 或 `login_ios` |
| deviceId | string | 是 | 设备 ID（来自 `GET /api/devices` 或按平台 `GET /api/devices/platform/ANDROID`） |
| applicationId | string | 是 | **应用 ID**（来自 `GET /api/applications/devices/{deviceId}/applications` 的某一项的 `id`，对应 **apps 表**） |
| config | string | 否 | JSON 字符串，如：`{"phoneNumber":"13800138000","verificationCode":"666888"}` |

**注意：** `applicationId` 必须是「该设备下已安装应用」的 ID（接口 `GET /api/applications/devices/{deviceId}/applications` 返回的列表里的 `id`），且该应用在 **apps** 表中有 **package_name**（如 `com.pwrd.steam.esports`）。若传错或传了设备 ID，会导致应用不启动。

**请求示例：**
```json
{
  "scenarioId": "login_android",
  "deviceId": "SEA_AL10",
  "applicationId": "某个应用的UUID（来自设备应用列表）"
}
```

**带 config 的请求示例：**
```json
{
  "scenarioId": "login_android",
  "deviceId": "SEA_AL10",
  "applicationId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "config": "{\"phoneNumber\":\"13800138000\",\"verificationCode\":\"666888\"}"
}
```

**响应示例（200）：**
```json
{
  "taskId": "创建出的任务UUID",
  "status": "RUNNING",
  "message": "任务已提交执行"
}
```

**curl 示例：**
```bash
# 将 DEVICE_ID、APPLICATION_ID 换成实际值
curl -s -X POST "http://localhost:3000/api/functional-test/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "scenarioId": "login_android",
    "deviceId": "SEA_AL10",
    "applicationId": "你的应用ID"
  }'
```

---

## 3. 查询任务状态（轮询用）

执行后可根据返回的 `taskId` 轮询任务状态，确认是否执行完成或失败。

| 项目 | 说明 |
|------|------|
| **方法** | `GET` |
| **URL** | `http://localhost:3000/api/tasks/{taskId}` |
| **响应** | 200 任务对象；404 未找到 |

**响应中关注字段：** `status`（PENDING / RUNNING / COMPLETED / FAILED）、`description` 等。执行失败时，可再查「任务执行记录」接口获取 `error_message`（若有）。

**curl 示例：**
```bash
curl -s "http://localhost:3000/api/tasks/这里填返回的taskId"
```

---

## 4. 其他相关接口（便于前端对齐）

- **设备列表（按平台）**  
  `GET /api/devices/platform/ANDROID`  
  返回该平台设备，用于「选择设备」。

- **某设备下的应用列表（选应用必用）**  
  `GET /api/applications/devices/{deviceId}/applications`  
  返回该设备下已安装应用，每项的 **id** 即上面 **applicationId** 应传的值。

- **功能质量测试任务列表**  
  `GET /api/functional-test/tasks`  
  查看功能质量测试任务列表（含刚创建的执行任务）。

---

## 5. 前端调用检查清单

1. **执行登录测试时是否传了 `applicationId`？**  
   必须传「应用」的 id，不能传成设备 id 或空。

2. **applicationId 来源是否正确？**  
   应来自 `GET /api/applications/devices/{deviceId}/applications` 返回的某一项的 `id`，且该应用有 `packageName`（如完美世界电竞包名）。

3. **请求 URL 是否正确？**  
   - 执行：`POST /api/functional-test/execute`（注意是 **execute**，且为 POST）。  
   - 基址是否为 `http://xxx:3000/api`（带 context-path `/api`）。

4. **请求体字段名是否与后端一致？**  
   - `scenarioId`、`deviceId`、`applicationId`（注意是 **applicationId**，不是 appId）。

按上述接口和清单核对前端调用即可。若仍有问题，可抓包对比请求 URL、Method、Body 与本文档是否一致。
