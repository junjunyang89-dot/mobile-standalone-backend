# 数据库表结构说明

## 最新 SQL 文件位置

数据库表结构定义在：`src/main/resources/db/schema.sql`

## 表结构说明

### 1. tasks 表（任务表）

存储测试任务信息，包含以下字段：

| 字段名 | 类型 | 说明 | 必填 |
|--------|------|------|------|
| id | VARCHAR(64) | 任务ID（主键） | ✅ |
| name | VARCHAR(255) | **任务名称**（1-60字符） | ✅ |
| description | TEXT | **备注信息**（最多80字符） | ❌ |
| platform | VARCHAR(20) | 平台类型：ANDROID, IOS, HARMONYOS | ✅ |
| device_id | VARCHAR(64) | **测试设备ID**（关联devices表） | ✅ |
| app_id | VARCHAR(64) | **已安装的应用ID**（关联apps表） | ✅ |
| task_type | VARCHAR(50) | **任务类型**（见下方说明） | ✅ |
| status | VARCHAR(20) | 任务状态：PENDING, RUNNING, COMPLETED, FAILED, STOPPED | ✅ |
| project_path | VARCHAR(500) | 项目路径 | ❌ |
| script_path | VARCHAR(500) | 测试脚本路径 | ❌ |
| created_at | DATETIME | 创建时间 | ✅ |
| updated_at | DATETIME | 更新时间 | ✅ |
| config | TEXT | 额外配置信息（JSON格式） | ❌ |

### 2. 任务类型（task_type）

支持的任务类型：

| 枚举值 | 中文名称 | 说明 |
|--------|---------|------|
| FUNCTIONAL_QUALITY_TEST | 功能体验基础质量测试 | 检测应用兼容性问题 |
| STABILITY_QUALITY_TEST | 稳定性基础质量测试 | 检测崩溃、内存泄漏等问题 |
| PERFORMANCE_QUALITY_TEST | 性能基础质量测试 | 评估应用性能表现 |
| POWER_CONSUMPTION_QUALITY_TEST | 功耗基础质量测试 | 检测后台资源占用 |
| PERFORMANCE_MONITORING_TEST | 性能指标监控测试 | 监控帧率、内存、CPU等指标 |
| REGRESSION_TEST | 回归测试 | 自定义自动化测试场景 |

### 3. apps 表（应用表）

存储已安装的应用信息：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | VARCHAR(64) | 应用ID（主键） |
| name | VARCHAR(255) | 应用名称 |
| package_name | VARCHAR(255) | 包名 |
| version | VARCHAR(50) | 版本号 |
| device_id | VARCHAR(64) | 设备ID（关联devices表） |
| platform | VARCHAR(20) | 平台类型 |
| icon_path | VARCHAR(500) | 图标路径 |
| install_time | BIGINT | 安装时间（时间戳） |
| file_size | BIGINT | 文件大小（字节） |

### 4. devices 表（设备表）

存储USB连接的测试设备信息：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | VARCHAR(64) | 设备ID（主键） |
| name | VARCHAR(255) | 设备名称 |
| platform | VARCHAR(20) | 平台类型 |
| version | VARCHAR(50) | 系统版本 |
| status | VARCHAR(20) | 连接状态：CONNECTED, DISCONNECTED |
| last_seen | DATETIME | 最后检测时间 |

## 数据存储说明

### 任务创建时存储的数据

1. **任务名称** (`name`) - 必填，存储在 `tasks.name`
2. **备注信息** (`description`) - 可选，存储在 `tasks.description`
3. **测试设备** (`device_id`) - 必填，存储在 `tasks.device_id`，关联 `devices` 表
4. **已安装应用** (`app_id`) - 必填，存储在 `tasks.app_id`，关联 `apps` 表
5. **任务类型** (`task_type`) - 必填，存储在 `tasks.task_type`，使用枚举值

### 应用存储

- **已安装的应用**：存储在 `apps` 表中
- 每个应用关联一个设备（`device_id`）
- 任务创建时通过 `app_id` 关联到具体的应用

## 索引说明

- `idx_status`: 任务状态索引，用于快速查询不同状态的任务
- `idx_platform`: 平台类型索引，用于按平台筛选
- `idx_device_id`: 设备ID索引，用于关联查询
- `idx_app_id`: 应用ID索引，用于关联查询
- `idx_task_type`: 任务类型索引，用于按类型筛选
- `idx_created_at`: 创建时间索引，用于时间排序

## 外键约束

- `tasks.device_id` → `devices.id` (ON DELETE SET NULL)
- `tasks.app_id` → `apps.id` (ON DELETE SET NULL)
