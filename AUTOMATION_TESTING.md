# UI自动化测试实现方案

## 概述

参考华为鸿蒙 DevEco Testing 工具，实现支持 Android、HarmonyOS、iOS 设备的 UI 自动化测试功能，包括"点点点操作"（点击、滑动、输入等）。

## 技术架构

### 1. 核心组件

#### AutomationEngine（自动化引擎）
- **功能**：提供基础的设备控制能力
- **支持操作**：
  - 点击（坐标点击、元素点击）
  - 滑动
  - 输入文本
  - 截图
  - 获取页面元素树
  - 等待元素出现
  - 检查元素存在

#### TestScriptExecutor（测试脚本执行器）
- **功能**：执行测试任务，协调自动化引擎
- **支持**：
  - 启动应用
  - 执行测试步骤
  - 收集测试结果

### 2. 平台实现方案

#### Android / HarmonyOS

**方案1：使用 ADB 命令（当前实现）**
- **优点**：无需额外依赖，直接使用系统工具
- **缺点**：功能有限，元素定位能力弱
- **适用场景**：简单的点击、滑动、输入操作

**命令示例：**
```bash
# 点击坐标
adb shell input tap x y

# 滑动
adb shell input swipe x1 y1 x2 y2 duration

# 输入文本
adb shell input text "hello"

# 截图
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png local.png

# 获取页面元素树
adb shell uiautomator dump /dev/tty
```

**方案2：集成 UiAutomator2（推荐）**
- **优点**：功能强大，支持元素定位、XPath、资源ID等
- **缺点**：需要安装 UiAutomator2 Server
- **实现方式**：
  1. 在设备上安装 `atx-agent` 和 `uiautomator2`
  2. 通过 HTTP API 调用 UiAutomator2 服务
  3. 支持更丰富的元素定位方式

**方案3：集成 Appium**
- **优点**：跨平台统一接口，功能完整
- **缺点**：需要运行 Appium Server，资源消耗较大
- **适用场景**：需要完整的 Appium 生态支持

#### iOS

**方案1：使用 WebDriverAgent（推荐）**
- **优点**：官方支持，功能完整
- **缺点**：需要编译安装，配置复杂
- **实现方式**：
  1. 在设备上安装 WebDriverAgent
  2. 通过 HTTP API 调用 WebDriverAgent
  3. 支持元素定位、点击、滑动等操作

**方案2：使用 idevice 工具**
- **优点**：简单易用
- **缺点**：功能有限，主要用于截图等基础操作
- **命令示例：**
```bash
# 截图
idevicescreenshot -u deviceId screenshot.png
```

**方案3：集成 Appium**
- **优点**：跨平台统一接口
- **缺点**：需要 Xcode、WebDriverAgent 等依赖

## 实现步骤

### 阶段1：基础功能实现（当前阶段）

✅ **已完成：**
- `AutomationEngine` 基础框架
- Android/HarmonyOS 基础操作（点击、滑动、输入、截图）
- iOS 基础操作框架

⏳ **待完善：**
- 元素定位功能（资源ID、文本、XPath）
- iOS 完整实现
- 错误处理和重试机制

### 阶段2：元素定位增强

**Android/HarmonyOS：**
1. 集成 UiAutomator2
   - 安装 `atx-agent` 到设备
   - 实现 HTTP API 调用
   - 支持元素查找和操作

2. 实现元素定位方法：
   - 资源ID定位：`resource-id:com.example:id/button`
   - 文本定位：`text:登录`
   - XPath定位：`xpath://android.widget.Button[@text='登录']`
   - 坐标定位：`coordinate:100,200`

**iOS：**
1. 集成 WebDriverAgent
   - 编译安装 WebDriverAgent
   - 实现 HTTP API 调用
   - 支持元素查找和操作

2. 实现元素定位方法：
   - Accessibility ID：`accessibility-id:loginButton`
   - 文本定位：`text:登录`
   - XPath定位：`xpath://XCUIElementTypeButton[@name='登录']`

### 阶段3：测试脚本支持

1. **脚本格式定义**
   - JSON 格式测试脚本
   - YAML 格式测试脚本
   - 支持步骤定义、断言、循环等

2. **脚本解析和执行**
   - 解析测试脚本
   - 按步骤执行
   - 记录执行结果

3. **示例脚本格式：**
```json
{
  "name": "登录测试",
  "steps": [
    {
      "action": "click",
      "selector": "text:登录",
      "timeout": 5
    },
    {
      "action": "wait",
      "selector": "text:用户名",
      "timeout": 10
    },
    {
      "action": "input",
      "selector": "id:username",
      "text": "testuser"
    },
    {
      "action": "input",
      "selector": "id:password",
      "text": "password123"
    },
    {
      "action": "click",
      "selector": "text:确认"
    },
    {
      "action": "screenshot",
      "path": "/tmp/login_result.png"
    },
    {
      "action": "assert",
      "type": "element_exists",
      "selector": "text:欢迎",
      "expected": true
    }
  ]
}
```

### 阶段4：高级功能

1. **图像识别**
   - 集成 OpenCV 或 Tesseract OCR
   - 支持图像匹配和文字识别
   - 用于复杂场景的元素定位

2. **性能监控**
   - CPU、内存、帧率监控
   - 网络流量监控
   - 电池消耗监控

3. **测试报告**
   - 生成详细的测试报告
   - 截图、日志、性能数据
   - Allure 报告集成

## 使用示例

### 1. 创建测试任务

```bash
POST /api/functional-test/tasks
{
  "taskName": "登录功能测试",
  "platform": "ANDROID",
  "deviceId": "device-123",
  "applicationId": "app-456"
}
```

### 2. 执行测试任务

```bash
POST /api/tasks/{taskId}/run
```

### 3. 测试执行流程

1. 系统自动启动应用
2. 执行预定义的测试步骤
3. 收集测试结果和截图
4. 生成测试报告

## 依赖工具安装

### Android/HarmonyOS

**基础工具（已安装）：**
- ADB (Android Debug Bridge)

**可选工具（增强功能）：**
```bash
# 安装 UiAutomator2
pip install uiautomator2
python -m uiautomator2 init
```

### iOS

**基础工具：**
```bash
# 安装 libimobiledevice
brew install libimobiledevice
brew install ideviceinstaller
```

**WebDriverAgent（推荐）：**
```bash
# 克隆 WebDriverAgent
git clone https://github.com/appium/WebDriverAgent.git
cd WebDriverAgent
./Scripts/bootstrap.sh

# 使用 Xcode 编译安装到设备
```

## 注意事项

1. **权限要求**
   - Android：需要开启 USB 调试
   - iOS：需要信任电脑，WebDriverAgent 需要开发者证书

2. **性能考虑**
   - 批量操作时注意延迟
   - 截图和元素树获取较耗时
   - 建议使用异步执行

3. **稳定性**
   - 添加重试机制
   - 处理网络延迟
   - 处理元素加载时间

4. **扩展性**
   - 支持自定义测试脚本
   - 支持插件机制
   - 支持多设备并行测试

## 后续优化方向

1. **集成成熟的测试框架**
   - Appium（跨平台）
   - UiAutomator2（Android）
   - WebDriverAgent（iOS）

2. **支持录制回放**
   - 录制用户操作
   - 生成测试脚本
   - 回放执行

3. **智能测试**
   - AI 辅助元素定位
   - 自动生成测试用例
   - 异常检测和处理
