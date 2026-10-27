# 智能 APP 自动化

入口：APP 自动化 → 智能 APP 自动化（`/#/smart-app`）。保留原有任务、流程、元素库和回归模块。

## 首次使用

1. 在设备池连接设备，确保 APP 已安装并登录。
2. 打开新入口，载入“主导航检查”或“CS赛事资讯评论草稿”示例。
3. 选择设备。可先点“读取当前页面”，查看截图、页面文字、ID、输入框及原始结构。
4. 检查步骤，保存并执行。
5. 在执行记录中查看每步结果；点“查看现场”读取当时的截图与页面结构。

两条示例均为 Android。资讯示例读取列表第一条的真实标题，保存为 newsTitle，然后打开并核验标题。它适用于跳转到赛事详情的新闻，默认只输入“不错”并核验，不发表评论。普通资讯详情页可能需要不同的步骤。示例会先有界返回到主导航，不清空 APP 数据。

当前版本是结构化脚本执行器。没有接入任意自然语言自动探索、运行时大模型自愈、OCR 或可调试 WebView DOM。可以让助手观察页面生成脚本，再导入平台执行。文字定位不依赖元素库；已有 ID 可以直接用于脚本。

## 脚本契约

UI 中导出脚本即可得到 JSON。导入总是创建副本，保存时由后端校验动作、目标和等待范围。`GET /api/smart-app/templates` 是最新示例的唯一来源。

顶层：name、platform（ANDROID / IOS / HARMONYOS）、packageName、variables、steps。导入不需要 id。

每步：name、action、target、value、saveAs、timeoutSeconds、direction、effect。

- LAUNCH：激活已安装 APP，value 留空使用 packageName，不清除数据。
- CLICK：读取当前 UI 树解析目标，点击当前边界中心；命令只提交一次。
- INPUT：目标必须为输入框；Android / iOS 清空后输入。显式 ASSERT 用于确认实际内容。
- WAIT：等待目标存在；不会点击。
- ASSERT：要求目标唯一存在；value 非空时还要求内容完全一致。
- READ：把当前目标文字保存为 saveAs；后续 `${newsTitle}` 引用。
- BACK：返回一次。
- BACK_TO：最多返回五次直到目标出现，出现后不再返回。用于回到可识别的页面。
- SWIPE：当前页面范围内向 UP 或 DOWN 滑动一次。
- SNAPSHOT：读取当前页面并保存现场。

目标可用 text 完全匹配、contains 包含文字、id、description、role=input/text；多个属性同时提供时取交集。withinText 限制在指定文字控件的子树或包围区域内。index 从零开始，只有明确设置时才按序号选择。没有目标可以等待，多个目标直接失败，不猜第一个。Android / WDA 读取 XML，Harmony 读取 JSON 层级。

变量以 `${name}` 引用。默认变量保存在用例；执行时覆盖变量只存在执行内存，不写入脚本或报告元数据。设备截图及页面结构仍可能包含输入内容，使用平台鉴权后的接口查看，按本地报告数据管理。

## 发布与执行语义

effect=WRITE 的步骤要求执行时 allowWrites=true；“发布”“发送”“删除”“确认支付”的直接文字点击即使漏标 effect 也要求明确允许。本检查是产品操作提示，不是任意脚本的完整业务副作用分析。

所有点击和输入都不自动重试。等待阶段可反复读取页面；提交后反馈不明确即失败并保存证据。是否发布成功应检查成功提示或本人评论，不能把点击成功当成业务成功。读取成功提示要考虑 Toast 的短生命周期，可配稳定的列表断言。

## 隔离及配置

- 新模块用例、报告及证据默认保存在执行节点 `./data/smart-app`，JSON 原子替换写入；不修改原有数据库表或元素库。多节点部署时，这些数据属于所选 worker，不是共享数据库目录。
- 前端所有 `/smart-app/*` 请求发送到设置中的 worker，设备选择与执行保持同一节点。
- 设备占用复用 DeviceLockService，新旧模块在同一后端实例内互斥。和原平台一样，多实例控制同一设备仍需分布式锁；外部 Appium/脚本不受这把内存锁约束。
- Android / iOS 使用新模块管理的独立 Appium 进程。默认 smart-app.appium-port=14731、smart-app.appium-command=appium。端口被其他服务占用时拒绝启动，不接管、不结束它。
- Android system/mjpeg 与 iOS WDA 端口分别从 18231 / 19231 / 17231 分配，跳过已占用端口，不杀进程。
- 会话结束后 quit，再释放设备锁；Appium 服务保留以供后续使用，在后端关闭时清理。取消等待当前驱动命令返回，不提前释放锁。
- 鸿蒙复用已有 PlatformRouter → HarmonyOSDriver → hdc/hmdriver2。iOS 使用现有 ios.wda.xcode-org-id / ios.wda.bundle-id 签名配置。
- 可配置 smart-app.storage-dir 指定持久化目录。部署时请保留该目录，避免丢失脚本与报告。
- 新入口加入 PlatformModules 和 PermissionRules，至少测试角色可见；受限账号需要管理员勾选新模块。证据接口保留登录鉴权，未加入公开截图名单。

不执行任意 Python / Shell 脚本；导入的是有限动作的 JSON。当前用例上限 100 步，每步目标等待 1–30 秒，总流程上限 30 分钟（底层设备命令的阻塞时间仍受驱动约束）。执行列表最近 100 条，历史文件保留在目录中；本版未提供报告删除或归档。

## 验证

单元测试：`mvn -Dtest=SmartAppTreeTest,SmartAppServiceTest,PermissionRulesTest test`。

真机验收需显式选择 Android 设备，确保它未被其他任务或外部会话使用：`SMART_APP_DEVICE=<serial> mvn -Dtest=SmartAppDeviceSmokeTest test`。该测试运行资讯评论草稿，不发布。测试创建的服务、会话和临时目录单独管理；不会写入平台用例库。

已在三星 S24 / Android 15 / 完美世界电竞 4.2.0.230 验证安卓真实链路。iOS 和原生鸿蒙有结构解析测试及驱动入口，尚未连接真机验收，不能视为已验证兼容。

前端标准 vue-tsc 1.8.27 与仓库现装 TypeScript 5.9.3 不兼容；新增 Vue 脚本单独通过 TypeScript 检查，Vite 构建通过。构建验证不等同于整个旧项目的类型检查通过。
