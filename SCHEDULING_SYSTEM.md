# 自动化测试调度系统架构文档

## 系统架构

```
graph TD
    subgraph "1. 调度层 (Scheduling Layer)"
        A[Quartz 调度器] -->|根据Cron触发| B[任务分发器 Task Dispatcher]
    end

    subgraph "2. 业务逻辑层 (Business Layer)"
        B --> C[用例引擎 Case Engine]
        C --> D[模块化对象库 POM]
        C --> E[安全校验模块 Anti-Misoperation]
    end

    subgraph "3. 驱动适配层 (Driver Adapter)"
        C --> F[平台路由 Platform Router]
        F --> G1[Android Driver]
        F --> G2[iOS Driver]
        F --> G3[HarmonyOS Driver]
    end

    subgraph "4. 物理设备层"
        G1 --> H1[Android Phone]
        G2 --> H2[iPhone]
        G3 --> H3[HarmonyOS Device]
    end

    subgraph "数据存储 (Persistence)"
        I[(MySQL: 用例/计划/报告)] <--> B
    end
```

## 核心组件

### 1. 调度层 (Scheduling Layer)

#### QuartzSchedulerService
- **功能**：管理 Quartz 调度器，负责测试计划的定时调度
- **主要方法**：
  - `schedulePlan(TestPlan plan)`: 添加测试计划到调度器
  - `updatePlanSchedule(TestPlan plan)`: 更新测试计划调度
  - `unschedulePlan(String planId)`: 移除测试计划
  - `pausePlan(String planId)`: 暂停测试计划
  - `resumePlan(String planId)`: 恢复测试计划
  - `triggerPlan(String planId)`: 立即执行测试计划

#### TaskDispatcher
- **功能**：接收 Quartz 调度器的触发，分发任务到用例引擎执行
- **主要方法**：
  - `executePlan(String planId)`: 执行测试计划

#### TestPlanJob
- **功能**：Quartz Job 实现，定时触发测试计划执行

### 2. 业务逻辑层 (Business Layer)

#### CaseEngine（用例引擎）
- **功能**：执行测试用例，协调驱动和页面对象模型
- **主要方法**：
  - `executeCase(TestCase, Device, String appId)`: 执行测试用例
  - `parseTestSteps(String stepsJson)`: 解析测试步骤（JSON格式）
  - `executeStep(PlatformDriver, String deviceId, TestStep, ...)`: 执行单个测试步骤
  - `validateExpectedResult(...)`: 验证预期结果

#### PageObjectService（页面对象模型服务）
- **功能**：管理页面对象和元素定义
- **主要方法**：
  - `createPageObject(PageObject)`: 创建页面对象
  - `findByPagePath(...)`: 根据页面路径查找页面对象
  - `updatePageObject(...)`: 更新页面对象

#### AntiMisoperationService（安全校验模块）
- **功能**：防止误操作，确保测试安全
- **主要方法**：
  - `validateStep(TestStep, Device, Application)`: 验证测试步骤是否安全
  - `validatePlan(String deviceId, String appId)`: 验证测试计划是否安全

### 3. 驱动适配层 (Driver Adapter)

#### PlatformRouter（平台路由）
- **功能**：根据设备平台选择对应的驱动
- **主要方法**：
  - `getDriver(Device.Platform)`: 根据平台获取驱动
  - `getDriver(Device)`: 根据设备获取驱动

#### PlatformDriver（平台驱动接口）
- **定义**：所有平台驱动需要实现的接口
- **主要方法**：
  - `launchApp(String deviceId, String packageName)`: 启动应用
  - `click(String deviceId, int x, int y)`: 点击坐标
  - `clickElement(String deviceId, String selector)`: 点击元素
  - `swipe(...)`: 滑动
  - `inputText(...)`: 输入文本
  - `takeScreenshot(...)`: 截图
  - `getPageSource(...)`: 获取页面元素树
  - `waitForElement(...)`: 等待元素出现
  - `isElementPresent(...)`: 检查元素是否存在
  - `clickByPageObject(...)`: 使用页面对象模型操作元素

#### AndroidDriver / IOSDriver / HarmonyOSDriver
- **功能**：各平台驱动的具体实现

## 数据库表结构

### test_cases（测试用例表）
- `id`: 用例ID
- `name`: 用例名称
- `description`: 用例描述
- `platform`: 平台类型
- `app_id`: 关联的应用ID
- `case_type`: 用例类型
- `steps`: 测试步骤（JSON格式）
- `expected_result`: 预期结果
- `priority`: 优先级（1-10）
- `status`: 状态（ACTIVE, INACTIVE, DEPRECATED）

### test_plans（测试计划表）
- `id`: 计划ID
- `name`: 计划名称
- `description`: 计划描述
- `cron_expression`: Cron表达式
- `timezone`: 时区
- `device_id`: 关联的设备ID
- `app_id`: 关联的应用ID
- `case_ids`: 关联的用例ID列表（JSON数组）
- `enabled`: 是否启用
- `status`: 状态（ACTIVE, PAUSED, COMPLETED）
- `last_run_time`: 最后执行时间
- `next_run_time`: 下次执行时间
- `run_count`: 执行次数

### test_reports（测试报告表）
- `id`: 报告ID
- `plan_id`: 关联的计划ID
- `case_id`: 关联的用例ID
- `device_id`: 关联的设备ID
- `app_id`: 关联的应用ID
- `execution_id`: 关联的执行ID
- `status`: 执行状态（PASSED, FAILED, SKIPPED, RUNNING）
- `start_time`: 开始时间
- `end_time`: 结束时间
- `duration`: 执行时长（毫秒）
- `screenshot_paths`: 截图路径列表（JSON数组）
- `error_message`: 错误信息
- `test_data`: 测试数据（JSON格式）
- `report_path`: 报告文件路径

### page_objects（页面对象模型表）
- `id`: 页面对象ID
- `name`: 页面对象名称
- `description`: 描述
- `platform`: 平台类型
- `app_id`: 关联的应用ID
- `page_path`: 页面路径或标识
- `elements`: 页面元素定义（JSON格式）

## API 接口

### 测试用例管理

```
GET    /api/test-cases                    # 获取所有用例
GET    /api/test-cases/{id}               # 获取用例详情
GET    /api/test-cases/platform/{platform} # 按平台获取用例
GET    /api/test-cases/app/{appId}        # 按应用获取用例
POST   /api/test-cases                    # 创建用例
PUT    /api/test-cases/{id}               # 更新用例
DELETE /api/test-cases/{id}               # 删除用例
```

### 测试计划管理

```
GET    /api/test-plans                    # 获取所有计划
GET    /api/test-plans/{id}               # 获取计划详情
GET    /api/test-plans/device/{deviceId}  # 按设备获取计划
GET    /api/test-plans/app/{appId}        # 按应用获取计划
POST   /api/test-plans                    # 创建计划
PUT    /api/test-plans/{id}               # 更新计划
DELETE /api/test-plans/{id}               # 删除计划
POST   /api/test-plans/{id}/enable        # 启用/禁用计划
POST   /api/test-plans/{id}/trigger       # 立即执行计划
POST   /api/test-plans/{id}/pause         # 暂停计划
POST   /api/test-plans/{id}/resume        # 恢复计划
```

## 使用示例

### 1. 创建测试用例

```json
POST /api/test-cases
{
  "name": "登录功能测试",
  "description": "测试用户登录功能",
  "platform": "ANDROID",
  "appId": "app-123",
  "caseType": "FUNCTIONAL",
  "steps": "[
    {\"action\": \"click\", \"selector\": \"text:登录\"},
    {\"action\": \"wait\", \"selector\": \"text:用户名\", \"timeout\": 10},
    {\"action\": \"input\", \"selector\": \"id:username\", \"text\": \"testuser\"},
    {\"action\": \"input\", \"selector\": \"id:password\", \"text\": \"password123\"},
    {\"action\": \"click\", \"selector\": \"text:确认\"},
    {\"action\": \"screenshot\", \"screenshot\": true}
  ]",
  "expectedResult": "{\"elementExists\": \"text:欢迎\"}",
  "priority": 8,
  "status": "ACTIVE"
}
```

### 2. 创建测试计划

```json
POST /api/test-plans
{
  "name": "每日登录测试",
  "description": "每天凌晨2点执行登录测试",
  "cronExpression": "0 0 2 * * ?",
  "timezone": "Asia/Shanghai",
  "deviceId": "device-123",
  "appId": "app-123",
  "caseIds": ["case-1", "case-2"],
  "enabled": true,
  "status": "ACTIVE"
}
```

### 3. Cron 表达式示例

- `0 0 2 * * ?`: 每天凌晨2点
- `0 0 */2 * * ?`: 每2小时执行一次
- `0 0 9 ? * MON-FRI`: 工作日上午9点
- `0 0 0 1 * ?`: 每月1号凌晨执行

## 测试步骤格式

测试步骤使用 JSON 数组格式，每个步骤包含以下字段：

```json
{
  "action": "click|swipe|input|wait|assert",
  "selector": "text:登录|id:button|resource-id:com.example:id/button",
  "text": "输入文本",
  "x1": 100,
  "y1": 200,
  "x2": 300,
  "y2": 400,
  "duration": 500,
  "timeout": 10,
  "delay": 1000,
  "screenshot": false,
  "pageObject": "LoginPage",
  "elementName": "loginButton"
}
```

### 支持的操作类型

- **click**: 点击操作
  - 使用 `selector` 指定元素
  - 或使用 `x1, y1` 指定坐标
  - 或使用 `pageObject` 和 `elementName` 使用页面对象模型

- **swipe**: 滑动操作
  - 使用 `x1, y1, x2, y2` 指定起点和终点
  - `duration` 指定滑动时长（毫秒）

- **input**: 输入文本
  - 使用 `selector` 指定输入框
  - `text` 指定输入内容

- **wait**: 等待操作
  - 使用 `selector` 和 `timeout` 等待元素出现
  - 或使用 `delay` 等待指定时间（毫秒）

- **assert**: 断言操作
  - 验证元素存在或文本内容

## 安全校验

### 危险操作列表
- `uninstall`: 卸载应用
- `delete`: 删除操作
- `format`: 格式化
- `factory_reset`: 恢复出厂设置

### 系统应用保护
以下系统应用不允许操作：
- `com.android.settings`
- `com.android.systemui`
- `com.android.launcher`

## 注意事项

1. **Quartz 调度器配置**
   - 使用 Spring 管理的 Scheduler
   - 支持 Spring Bean 注入到 Job 中
   - 配置了 AutowiringSpringBeanJobFactory

2. **JSON 序列化**
   - 使用自定义 TypeHandler 处理 JSON 字段
   - `JsonListTypeHandler`: 处理 List<String>
   - `JsonMapTypeHandler`: 处理 Map<String, PageObject.PageElement>

3. **数据库迁移**
   - 执行 `schema_test_automation.sql` 创建新表
   - 表结构与现有表兼容

4. **错误处理**
   - 所有操作都有异常处理
   - 记录详细的日志信息
   - 返回友好的错误信息
