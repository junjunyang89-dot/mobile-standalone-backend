# 完美世界电竞 Android 页面元素导入说明

将《Android 页面元素清单》中的元素通过接口批量落入 `page_objects` 表。

## 1. 准备 appId

- **appId** 为应用表 `apps` 的主键。若「完美世界电竞」尚未入库，可先：
  - 在设备上安装该 App 后，调用 `GET /api/applications/devices/{deviceId}/applications` 获取该设备应用列表，取「完美世界电竞」对应的 `id`；
  - 或先创建一条应用记录（如 `POST /api/applications`），再用返回的 `id` 作为 appId。
- 记下该 **appId**（字符串，如 UUID）。

## 2. 生成导入 JSON

在项目根目录执行（需 Python3）：

```bash
# 方式一：输出到文件，再在文件中把 __APP_ID__ 替换为你的 appId
python3 scripts/generate_esports_android_import.py > esports_android_import.json
# 将 __APP_ID__ 替换为实际 appId（如用 sed）
sed -i '' 's/__APP_ID__/你的appId/g' esports_android_import.json   # macOS
# 或
sed -i 's/__APP_ID__/你的appId/g' esports_android_import.json     # Linux

# 方式二：通过环境变量传入 appId 直接生成
APP_ID=你的appId python3 scripts/generate_esports_android_import.py > esports_android_import.json
```

## 3. 调用导入接口

确保后端已启动（如 `http://127.0.0.1:3000`），执行：

```bash
curl -X POST http://127.0.0.1:3000/api/page-objects/import \
  -H "Content-Type: application/json" \
  -d @esports_android_import.json
```

返回示例：

```json
{
  "created": 20,
  "ids": ["uuid1", "uuid2", ...],
  "errors": null
}
```

## 4. 接口说明

- **POST** `/api/page-objects/import`
- **Body**：`{ "appId": "应用ID", "platform": "ANDROID", "pageObjects": [ { "pagePath": "Settings_SideMenu", "name": "设置-侧边栏", "elements": { "SIDE_MENU_BUTTON": { "name": "SIDE_MENU_BUTTON", "selector": "com.pwrd.steam.esports:id/sideView", "selectorType": "id", "description": "", "timeout": 10 }, ... } }, ... ] }`
- **platform** 仅支持 `ANDROID` / `IOS` / `HARMONYOS`；当前脚本为 Android。

## 5. 扩展更多页面/元素

`scripts/generate_esports_android_import.py` 中已包含设置、任务大厅、我的消息、主/登录/验证码、我的装扮、我的资产等页面。若需按《Android 页面元素清单》补充更多页面或元素，可在脚本中仿照 `page("pagePath", "name", [ (元素名, type, value), ... ])` 增加条目后重新执行步骤 2、3。
