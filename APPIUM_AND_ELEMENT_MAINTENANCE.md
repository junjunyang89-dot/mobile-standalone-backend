# 基于 Appium 的自动化测试与元素维护

本文说明如何基于 Appium 做 Android/iOS 自动化，以及**安卓与 iOS 元素如何分别单独维护**（推荐方式与实现）。

---

## 一、元素维护方式（安卓 / iOS 分别维护）

### 推荐方式：按平台分表维护（一页两录）

- **同一逻辑页面**（如「登录页」）在库里存 **两条记录**：一条 `platform=ANDROID`，一条 `platform=IOS`。
- **页面标识**：用 `page_path` 表示同一页（如 `Login`、`Home`），Android 和 iOS 用相同的 `page_path`，通过 `platform` 区分。
- **元素名**：两边用**相同逻辑名**（如 `loginBtn`、`usernameInput`），便于用例里只写「页面 + 元素名」，运行时按当前设备平台自动取对应选择器。
- **选择器**：Android 和 iOS 各自维护自己的 `elements` JSON（`selector` / `selectorType` 不同），互不影响。

这样做的优点：

- 安卓和 iOS 元素完全独立，改一边不影响另一边。
- 用例里只写「页面路径 + 元素名」，不写平台，运行时按设备平台自动选 POM。
- 适合「固定一个 App、多平台」的场景。

### 表结构（已有）

`page_objects` 表已支持按平台区分：

- `platform`：ANDROID / IOS / HARMONYOS
- `app_id`：固定 App 的 ID
- `page_path`：页面标识（如 `Login`）
- `elements`：JSON，key 为元素名，value 为 `{ selector, selectorType, description, timeout }`

**唯一性**：`(platform, app_id, page_path)` 唯一，即同一 App 下同一页面对每个平台一条记录。

### 维护流程示例（固定 App）

1. **Android 登录页**
   - 新增一条：`platform=ANDROID`，`app_id=你的应用ID`，`page_path=Login`
   - `elements` 示例：
     ```json
     {
       "loginBtn": { "selector": "com.example:id/btn_login", "selectorType": "id", "description": "登录按钮", "timeout": 10 },
       "usernameInput": { "selector": "com.example:id/et_username", "selectorType": "id", "description": "用户名", "timeout": 10 }
     }
     ```

2. **iOS 登录页**
   - 再新增一条：`platform=IOS`，`app_id=你的应用ID`，`page_path=Login`
   - `elements` 示例（选择器与 Android 不同）：
     ```json
     {
       "loginBtn": { "selector": "Login", "selectorType": "accessibilityId", "description": "登录按钮", "timeout": 10 },
       "usernameInput": { "selector": "Username", "selectorType": "accessibilityId", "description": "用户名", "timeout": 10 }
     }
     ```

3. **用例步骤**里只写：`pageObject=Login`，`elementName=loginBtn`；运行时根据当前设备平台（Android/iOS）查对应那条 POM，用对应选择器执行。

### 选择器格式（与 Appium 对齐）

元素里的 `selectorType` 建议使用下面几种，后端/Appium 会转成对应查找方式：

| selectorType     | Android 含义           | iOS 含义                | 示例 value              |
|------------------|------------------------|--------------------------|--------------------------|
| id               | resource-id            | 一般不用                 | `com.example:id/btn_ok`  |
| accessibilityId  | content-description   | accessibility identifier | `登录`                   |
| xpath            | XPath                  | XPath                    | `//*[@text='登录']`      |
| name             | text（部分场景）       | name/label               | `登录`                   |
| className        | class                  | class                    | `android.widget.Button`  |

同一元素在 Android 可能是 `id`，在 iOS 可能是 `accessibilityId`，因此**按平台分两条 POM 记录**最清晰。

### 后端接口（按 app + 平台查 POM，便于按平台维护元素）

- **服务层**：`PageObjectService.getPageObjectsByAppIdAndPlatform(appId, platform)` — 返回某 App 某平台下的所有页面对象列表，便于按「安卓 / iOS」分别维护元素。
- **单条**（用例执行时用）：`findByPagePath(platform, appId, pagePath)`，即按 `platform + appId + pagePath` 查一条。
- 如需 REST 接口（如 `GET /api/page-objects?appId=xxx&platform=ANDROID`），可在 Controller 中调用上述 service 方法即可。

---

## 二、基于 Appium 的自动化

### 前置条件

- 本机或远程已启动 **Appium Server**（如 `appium` 或 `appium --address 0.0.0.0 --port 4723`）。
- 设备已连接（USB 或 WiFi），Android 需开启 USB 调试，iOS 需信任电脑、必要时配置 WebDriverAgent。
- 固定 App：Android 为 package（及可选 main activity），iOS 为 bundleId。

### 后端配置（application.yml）

```yaml
appium:
  enabled: true   # 使用 Appium 驱动（false 则仍用 ADB/idevice）
  server-url: http://127.0.0.1:4723
  new-command-timeout: 300
  implicit-wait: 10
```

可选：为固定 App 配置默认 capability（不配则用任务/用例里的应用信息）：

```yaml
appium:
  android:
    app-package: com.example.yourapp
    app-activity: .MainActivity
  ios:
    bundle-id: com.example.yourapp
```

### 执行流程简述

1. 任务/用例执行时，根据设备得到 `platform`（ANDROID/IOS）和 `appId`。
2. 根据 `appId` 查到应用得到 `packageName`（Android 为 package，iOS 为 bundleId）。
3. 若启用 Appium：用 `appium.server-url` 创建 Appium 会话（capabilities 含 `udid`/deviceId、`platformName`、`appPackage`/`appActivity` 或 `bundleId`）。
4. 步骤里若带 `pageObject` + `elementName`：按 `platform + appId + pagePath` 查出该平台的 POM，取 `elements[elementName]` 的 `selectorType` + `selector`，转成 Appium 的 By/AppiumBy，再执行 click/input 等。
5. 元素查找约定与上表一致（id、accessibilityId、xpath、name、className），保证安卓和 iOS 分别维护的 selector 都能被正确解析。

### 与现有实现的衔接

- **元素维护**：已按「一页两录」（Android 一条、iOS 一条）在 `page_objects` 中实现，无需改表结构。
- **用例层**：步骤只写 `pageObject` + `elementName`，不写平台；运行时由 CaseEngine 根据当前设备平台加载对应 POM。
- **驱动层**：`appium.enabled=true` 时使用 Appium 驱动（基于 Appium Java Client），否则沿用现有 ADB/idevice 驱动。

---

## 三、小结

- **元素安卓和 iOS 分别单独维护**：采用 **按平台分表维护** —— 同一 `page_path` 下，`platform=ANDROID` 与 `platform=IOS` 各一条记录，元素名一致、选择器按平台各自配置。
- **固定 App**：所有 POM 使用同一 `app_id`，Android 与 iOS 各维护一套 `page_objects` 即可。
- **基于 Appium**：开启 `appium.enabled` 并配置 `server-url` 后，后端使用 Appium 驱动执行点击、输入等，元素选择器与上述 `selectorType` 约定一致即可同时在安卓和 iOS 上运行。
