# 应用 ID（appId）与执行测试时如何关联到元素

## 一、应用 ID（appId）是什么数据？

- **appId** 是 **应用表（`apps`）里那条应用记录的主键 `id`**。
- 每条应用记录表示：某个设备上安装的某一个 App（例如「微信」在这台安卓机上的记录、或「我的 App」在这台 iOS 机上的记录）。
- 表里有：`id`、`name`、`packageName`（Android 包名 / iOS BundleId）、`platform`、`deviceId` 等。

### 前端如何拿到 appId？

1. **按设备查已安装应用**（推荐）：  
   `GET /api/applications/devices/{deviceId}/applications`  
   返回该设备上的应用列表，**每条记录的 `id` 就是 appId**。前端用下拉框展示「应用名称」，用户选中后，把该条的 `id` 作为「应用 ID」提交。

2. **应用列表**：  
   `GET /api/applications`  
   返回所有应用，同样用每条记录的 `id` 作为 appId。

所以截图里的「应用 ID」**不要手输**，应从「选择应用」下拉里选一个应用，用该应用的 **`id`** 作为 appId 提交；前端可以先按设备筛出应用列表，再让用户选一个。

---

## 二、执行某个测试时，如何关联到具体的元素数据？

关联链是：**任务/用例里的 appId + 设备平台 + 步骤里的页面标识** → 查到一条 **页面对象** → 再按 **步骤里的元素名** 取到选择器，在设备上操作。

### 1. 任务/用例里存了什么？

- 创建任务或用例时，会选 **设备** 和 **应用**。
- 保存后：
  - **任务**：`appId` = 选中的那条应用的 `id`，`platform` 来自设备（ANDROID/IOS）。
  - **用例**：`appId`、`platform` 同样来自「要测哪个应用、在什么平台上跑」。

### 2. 步骤里写的是什么？

- 每一步会写：
  - **pageObject**：页面标识，和「页面对象」里的 **页面标识（pagePath）** 一致，如 `Login`、`Home`。
  - **elementName**：元素名，和该页面对象里 **elements** 的 key 一致，如 `loginBtn`、`usernameInput`。

### 3. 执行时怎么用？

- 执行到某一步时，引擎会：
  1. 用 **当前用例的 platform**、**当前用例的 appId**、**当前步骤的 pageObject（pagePath）** 去查 **page_objects** 表；
  2. 得到 **一条** 页面对象（同一 app、同一平台、同一页面只有一条）；
  3. 从这条的 **elements** 里用 **elementName** 取出选择器（selector、selectorType 等）；
  4. 用该选择器在当前设备上做点击、输入等操作。

所以：**appId + platform + pagePath** 唯一确定「哪个应用的哪一页」；**elementName** 确定「这一页里的哪个元素」。执行测试时，就是用任务/用例的 appId 和平台，加上步骤里的 pagePath 和 elementName，自动关联到你维护好的元素数据。

---

## 三、小结

| 概念 | 含义 |
|------|------|
| **appId** | 应用表 `apps` 的主键 `id`，前端从「应用列表」接口取，用户选应用后传该条的 `id` |
| **关联到元素** | 执行时用 **(appId, platform, pagePath)** 查 `page_objects` 得到该页，再用 **elementName** 从 elements 里取选择器 |

前端建议：**「应用 ID」用下拉选择已选设备下的应用列表里的 `id`，不要让用户手输。**
