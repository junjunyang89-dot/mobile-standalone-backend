# 纯鸿蒙 NEXT 自动化（hdc only）

目标：平台侧用 **hdc** 发现设备、装 hap、启动/点击/截图；**不走 adb**。

## 今晚最小闭环

1. 安装 **hdc**（本机当前通常没有独立包，需经 DevEco）
2. `pip install -r scripts/harmony/requirements.txt`
3. USB 连真机，授权调试
4. `hdc list targets` 能看到序列号
5. 重启后端 → 设备列表出现 `HARMONYOS`
6. 上传 `.hap` 安装 / 流程里启动应用试跑

## 1. 安装 hdc

华为已把 toolchains 收进 DevEco Studio SDK，**没有稳定的独立 brew 包**。

1. 下载安装 [DevEco Studio](https://developer.huawei.com/consumer/cn/deveco-studio/)（需华为账号）
2. 首次向导或 `Settings → SDK` 勾选 **HarmonyOS SDK** 与 **Toolchains**
3. macOS 常见路径：

```text
~/Library/Huawei/Sdk/harmonyos/<version>/toolchains/hdc
```

或 DevEco 内置：

```text
/Applications/DevEco-Studio.app/Contents/sdk/.../toolchains/hdc
```

4. 写入 shell 或配置后端：

```bash
export PATH="$PATH:$HOME/Library/Huawei/Sdk/harmonyos/<version>/toolchains"
hdc version
hdc list targets
```

或在 `application.yml`：

```yaml
harmony:
  hdc-path: /绝对路径/toolchains/hdc
```

也可用环境变量 `HDC_PATH`（见 yml 中 `${HDC_PATH:}`）。

一键检查：

```bash
bash scripts/harmony/setup_check.sh
```

## 2. Python UI 桥（hmdriver2）

```bash
pip3 install -r scripts/harmony/requirements.txt
python3 scripts/harmony/hmdriver_bridge.py ping
python3 scripts/harmony/hmdriver_bridge.py list-devices
```

依赖本机 `hdc` 在 PATH（或 hmdriver2 能直接调到 `hdc`）。

## 3. 平台改动说明

| 能力 | 实现 |
|------|------|
| 设备扫描 | `DeviceManager.scanHarmonyOSDevices` → `hdc list targets` |
| 装包 | `hdc install` / hmdriver2 `install_app`（`.hap`） |
| 应用列表 | hmdriver2 `list_apps`，失败则 `bm dump -a` |
| UI 点击/滑动/输入/截图/树 | `HarmonyOSDriver` → `HmdriverBridge` → hmdriver2 |
| 路由 | `HARMONYOS` **不再**走 Appium Android / adb |

选择器约定（与 Android 文案风格接近）：

- `text:登录`
- `id:xxx` / `resource-id:xxx`
- `desc:xxx`
- `xpath://...`

启动应用包名可写 `bundleName`，或 `bundleName/AbilityName`。

## 4. 真机注意

- 开发者模式、USB 调试（hdc）、信任电脑
- 部分机型需「仅充电」改成「文件传输/调试」
- 无线调试：`hdc tconn <ip>:<port>` 后 `list targets` 仍应看到该目标

## 5. 今晚可验收清单

- [ ] `hdc version` 有输出
- [ ] 插上手机后 `hdc list targets` 非 Empty / Unauthorized
- [ ] `setup_check.sh` / bridge `ping` 成功
- [ ] 后端日志出现 `hdc 已就绪` / `发现鸿蒙设备(hdc)`
- [ ] 设备页平台为 HARMONYOS
- [ ] 元素管理 / 流程编排 → 真机点选：鸿蒙机能刷新截图并点选推荐 `text`/`id`
- [ ] 流程：启动应用（填 bundle 或 bundle/Ability）→ 点击 `text:xxx` 能跑通

## 6. 平台集成说明（已落地）

| 能力 | 路径 |
|------|------|
| 设备扫描 | `hdc list targets` → 设备管理 HARMONYOS |
| 真机点选 | `DeviceInspectService` → `HarmonyOSDriver` 截图 + dump → JSON 转 XML 命中 |
| 流程启动 | `LAUNCH_APP` 鸿蒙分支 → hdc/hmdriver2 |
| 点击/输入/等待 | `HarmonyOSDriver`（不再误走 Appium Android） |
| 选择器 | 鸿蒙 `text`→`text:`，`id`→`id:`（见 `UiFlowExecutor.toDriverSelector`） |

应用字典：鸿蒙 bundle 可先写在 **Android 包名字段**，或启动步骤直接填 `packageName`（例如 `com.pwrd.steam.esports.hos`）。Ability 可写在 activity 参数，启动时拼成 `bundle/Ability`。

## 7. 边界

- 与 Android 关键字库 100% 对齐、复杂断言 → 后续迭代
- 旧「adb 把华为机标成鸿蒙」逻辑已去掉；双框架安卓机仍归 **ANDROID**
- Appium Harmony 驱动未接入；主路径为 **hmdriver2**
