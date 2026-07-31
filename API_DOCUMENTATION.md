# 后端 API 接口文档

## 基础信息

- **Base URL**: `http://localhost:3000/api`
- **Content-Type**: `application/json` (除文件上传接口外)

---

## 设备管理接口

### 1. 获取所有设备

```
GET /api/devices
```

**响应：**
```json
[
  {
    "id": "device-id-123",
    "name": "Android Device",
    "platform": "ANDROID",
    "version": "13",
    "status": "CONNECTED",
    "lastSeen": "2026-01-15T15:30:00"
  }
]
```

### 2. 按平台获取设备（推荐使用）

```
GET /api/devices/platform/{platform}
```

**路径参数：**
- `platform`: `ANDROID` | `IOS` | `HARMONYOS`

**说明：**
- 系统每3秒自动扫描USB连接的设备
- 设备会自动识别平台并分类存储
- 选择平台后调用此接口，只返回该平台下的设备

**响应：**
```json
[
  {
    "id": "device-id-123",
    "name": "Android Device",
    "platform": "ANDROID",
    "version": "13",
    "status": "CONNECTED",
    "lastSeen": "2026-01-15T15:30:00"
  }
]
```

### 3. 获取单个设备信息

```
GET /api/devices/{id}
```

### 4. 手动触发设备扫描

```
POST /api/devices/scan
```

**响应：**
```json
"设备扫描完成"
```

---

## 应用管理接口

### 1. 获取所有应用

```
GET /api/applications
```

### 2. 按平台获取已安装应用（推荐使用）

```
GET /api/applications/platform/{platform}
```

**路径参数：**
- `platform`: `ANDROID` | `IOS` | `HARMONYOS`

**响应：**
```json
[
  {
    "id": "app-id-123",
    "name": "测试应用",
    "packageName": "com.example.app",
    "version": "1.0.0",
    "platform": "ANDROID",
    "deviceId": "device-id-123",
    "installTime": 1705312354000,
    "fileSize": 1024000
  }
]
```

### 3. 上传并安装应用

```
POST /api/applications/upload
Content-Type: multipart/form-data
```

**表单参数：**
- `file`: 应用文件（必填）
  - Android: `.apk`
  - iOS: `.ipa` 或 `.app`
  - HarmonyOS: `.hap` 或 `.app`
- `platform`: 平台类型（必填）：`ANDROID` | `IOS` | `HARMONYOS`
- `deviceId`: 设备ID（可选，如果提供会直接安装到设备）

**响应：**
```json
{
  "applicationId": "app-id-123",
  "applicationName": "测试应用",
  "platform": "ANDROID",
  "installPath": "/path/to/file",
  "message": "应用上传成功"
}
```

### 4. 安装应用到设备

```
POST /api/applications/{id}/install?deviceId={deviceId}
```

**路径参数：**
- `id`: 应用ID

**查询参数：**
- `deviceId`: 设备ID（必填）

**响应：**
```json
{
  "success": true,
  "message": "应用安装成功"
}
```

---

## 任务管理接口

### 1. 创建功能体验基础质量测试任务

```
POST /api/functional-test/tasks
Content-Type: application/json
```

**请求体：**
```json
{
  "taskName": "Task-0115-152554",     // 必填，1-60字符
  "remarks": "测试备注信息",            // 可选，最多80字符
  "platform": "ANDROID",               // 必填：ANDROID | IOS | HARMONYOS
  "deviceId": "device-id-123",         // 必填，设备ID
  "applicationId": "app-id-456"       // 必填，应用ID
}
```

**响应：**
```json
{
  "taskId": "task-uuid-123",
  "taskName": "Task-0115-152554",
  "status": "PENDING",
  "createdAt": "2026-01-15T15:25:54"
}
```

**说明：**
- 此接口专门用于创建"功能体验基础质量测试"任务
- 任务类型自动设置为 `FUNCTIONAL_QUALITY_TEST`
- 任务状态初始为 `PENDING`

### 2. 获取所有任务

```
GET /api/tasks
```

**响应：**
```json
[
  {
    "id": "task-id-123",
    "name": "Task-0115-152554",
    "description": "测试备注",
    "platform": "ANDROID",
    "deviceId": "device-id-123",
    "appId": "app-id-456",
    "taskType": "FUNCTIONAL_QUALITY_TEST",
    "status": "PENDING",
    "createdAt": "2026-01-15T15:25:54",
    "updatedAt": "2026-01-15T15:25:54"
  }
]
```

### 3. 获取任务详情

```
GET /api/tasks/{id}
```

### 4. 运行任务

```
POST /api/tasks/{id}/run
```

### 5. 停止任务

```
POST /api/tasks/{id}/stop
```

---

## 数据模型

### Device（设备）

```json
{
  "id": "string",              // 设备ID（设备序列号）
  "name": "string",            // 设备名称
  "platform": "ANDROID|IOS|HARMONYOS",  // 平台类型
  "version": "string",         // 系统版本
  "status": "CONNECTED|DISCONNECTED",    // 连接状态
  "lastSeen": "datetime"       // 最后检测时间
}
```

### Application（应用）

```json
{
  "id": "string",              // 应用ID
  "name": "string",            // 应用名称
  "packageName": "string",      // 包名
  "version": "string",          // 版本号
  "platform": "ANDROID|IOS|HARMONYOS",  // 平台类型
  "deviceId": "string",         // 设备ID
  "installTime": "long",        // 安装时间（时间戳）
  "fileSize": "long"            // 文件大小（字节）
}
```

### Task（任务）

```json
{
  "id": "string",              // 任务ID
  "name": "string",            // 任务名称
  "description": "string",     // 备注信息
  "platform": "ANDROID|IOS|HARMONYOS",  // 平台类型
  "deviceId": "string",        // 设备ID
  "appId": "string",          // 应用ID
  "taskType": "FUNCTIONAL_QUALITY_TEST|STABILITY_QUALITY_TEST|...",  // 任务类型
  "status": "PENDING|RUNNING|COMPLETED|FAILED|STOPPED",  // 任务状态
  "createdAt": "datetime",     // 创建时间
  "updatedAt": "datetime"      // 更新时间
}
```

### 任务类型（taskType）

- `FUNCTIONAL_QUALITY_TEST` - 功能体验基础质量测试
- `STABILITY_QUALITY_TEST` - 稳定性基础质量测试
- `PERFORMANCE_QUALITY_TEST` - 性能基础质量测试
- `POWER_CONSUMPTION_QUALITY_TEST` - 功耗基础质量测试
- `PERFORMANCE_MONITORING_TEST` - 性能指标监控测试
- `REGRESSION_TEST` - 回归测试

---

## 前端集成建议

### 创建任务流程

1. **选择平台** → 调用 `GET /api/devices/platform/{platform}` 获取设备列表
2. **选择设备** → 从设备列表中选择
3. **选择应用** → 调用 `GET /api/applications/platform/{platform}` 获取应用列表
4. **填写任务信息** → 任务名称、备注
5. **提交创建** → 调用 `POST /api/functional-test/tasks`

### 设备自动识别

- 系统后台每3秒自动扫描USB设备
- 设备自动识别平台并分类
- 前端只需按平台调用接口获取设备列表即可

---

## 错误响应

所有接口在出错时返回相应的HTTP状态码：

- `200 OK` - 请求成功
- `400 Bad Request` - 请求参数错误
- `404 Not Found` - 资源不存在
- `500 Internal Server Error` - 服务器内部错误

错误响应示例：
```json
{
  "error": "错误信息描述"
}
```

---

## 注意事项

1. 所有接口路径都需要 `/api` 前缀
2. 平台类型必须使用大写：`ANDROID`、`IOS`、`HARMONYOS`
3. 设备ID是设备序列号，由系统自动识别
4. 应用ID是应用在数据库中的唯一标识
5. 任务创建后状态为 `PENDING`，需要调用运行接口启动任务
