# 元素存放与读取说明（安卓 / iOS 分别存放、分别读取）

## 一、元素放在哪里

- **存储位置**：MySQL 数据库，表名 **`page_objects`**。
- **一条记录**：表示「某个应用、某个平台、某一页」下的所有元素。
- **安卓和 iOS 分别存放**：同一逻辑页（如登录页）存 **两条记录**，通过 `platform` 区分：
  - 一条 `platform = 'ANDROID'`，`elements` 里是安卓的选择器；
  - 一条 `platform = 'IOS'`，`elements` 里是 iOS 的选择器。

### 表结构（与平台相关字段）

| 字段          | 说明 |
|---------------|------|
| id            | 主键 |
| name          | 页面对象名称（如「登录页」） |
| platform      | **ANDROID / IOS / HARMONYOS**，决定是安卓还是 iOS 元素 |
| app_id        | 关联的应用 ID（固定 App 时同一个 appId） |
| page_path     | 页面标识（如 `Login`、`Home`），同一页在安卓和 iOS 用相同值 |
| elements      | **JSON**，key 为元素名，value 为 `{ selector, selectorType, description, timeout }` |

### 示例：登录页「安卓一条、iOS 一条」

- **Android 记录**：`platform='ANDROID'`, `app_id='your-app-id'`, `page_path='Login'`  
  `elements` 示例：
  ```json
  {
    "loginBtn": { "selector": "com.example:id/btn_login", "selectorType": "id", "description": "登录按钮", "timeout": 10 },
    "usernameInput": { "selector": "com.example:id/et_username", "selectorType": "id", "description": "用户名", "timeout": 10 }
  }
  ```

- **iOS 记录**：`platform='IOS'`, `app_id='your-app-id'`, `page_path='Login'`  
  `elements` 示例（选择器与安卓不同）：
  ```json
  {
    "loginBtn": { "selector": "Login", "selectorType": "accessibilityId", "description": "登录按钮", "timeout": 10 },
    "usernameInput": { "selector": "Username", "selectorType": "accessibilityId", "description": "用户名", "timeout": 10 }
  }
  ```

结论：**元素就放在 `page_objects` 表里，安卓和 iOS 通过不同行（不同 `platform`）分别存放。**

---

## 二、如何读取（分别读安卓、分别读 iOS）

### 1. 按「应用 + 平台」读该平台下所有页面对象（推荐用于维护/列表）

- **只读安卓元素**：  
  `PageObjectService.getPageObjectsByAppIdAndPlatform(appId, PageObject.Platform.ANDROID)`  
  → 返回该 app 下所有 **Android** 的页面对象列表（每条的 `elements` 都是安卓选择器）。

- **只读 iOS 元素**：  
  `PageObjectService.getPageObjectsByAppIdAndPlatform(appId, PageObject.Platform.IOS)`  
  → 返回该 app 下所有 **iOS** 的页面对象列表（每条的 `elements` 都是 iOS 选择器）。

### 2. 按「应用 + 平台 + 页面」读某一页（用于执行用例时取元素）

- **读安卓某一页**：  
  `PageObjectService.findByPagePath("ANDROID", appId, pagePath)`  
  → 返回该页的 **Android** 那条记录（含该页的安卓元素）。

- **读 iOS 某一页**：  
  `PageObjectService.findByPagePath("IOS", appId, pagePath)`  
  → 返回该页的 **iOS** 那条记录（含该页的 iOS 元素）。

### 3. 用例执行时是怎么读的（自动按当前平台读）

- 执行用例时，**CaseEngine** 会根据当前用例的 **平台**（Android 或 iOS）自动选对应那条记录：
  - 调用：`pageObjectService.findByPagePath(testCase.getPlatform().name(), testCase.getAppId(), step.getPageObject())`
  - 跑在 Android 上 → 读 `platform='ANDROID'` 的那条；
  - 跑在 iOS 上 → 读 `platform='IOS'` 的那条。

所以：**存放是分平台（分记录），读取也是分平台（按 platform 查），安卓和 iOS 完全分别存放、分别读取。**

---

## 三、小结

| 需求               | 存放位置              | 读取方式 |
|--------------------|-----------------------|----------|
| 安卓元素放哪       | `page_objects` 表，`platform='ANDROID'` 的行 | 见下表 |
| iOS 元素放哪       | `page_objects` 表，`platform='IOS'` 的行     | 见下表 |
| 只读安卓所有页面   | —                     | `getPageObjectsByAppIdAndPlatform(appId, ANDROID)` |
| 只读 iOS 所有页面  | —                     | `getPageObjectsByAppIdAndPlatform(appId, IOS)` |
| 读安卓某一页元素   | —                     | `findByPagePath("ANDROID", appId, pagePath)` |
| 读 iOS 某一页元素  | —                     | `findByPagePath("IOS", appId, pagePath)` |
| 执行时按当前平台读 | —                     | CaseEngine 用 `findByPagePath(当前平台, appId, pagePath)` |

元素 **全部在 MySQL 的 `page_objects` 表**，安卓和 iOS 通过 **不同行（不同 `platform`）分别存放**，读取时通过 **传入 ANDROID 或 IOS** 分别读取即可。
