# 元素点选：Inspector 式控件树 — 前端对接说明

## 这次改了什么

原来只有「点一下截图 → 后端猜你选的是哪个控件」这一条路：整棵控件树只活在后端内存里，
前端拿不到，选得准不准全靠 `DeviceInspectService` 里那几千行启发式。猜错了只能继续加规则，
里面已经有针对具体 App、具体控件的硬编码。

现在把树交出去，选哪个节点变成人的事，后端只负责给一个默认推荐：

| 能力 | Appium Inspector | 改造前 | 改造后 |
|------|------------------|--------|--------|
| 整棵控件树 | 有 | 无（只在后端内存） | `GET /inspect/hierarchy` |
| 所有节点边框叠加 / hover 高亮 | 有 | 无 | `node.screenBounds` 直接画 |
| 点树选中任意节点取 locator | 有 | 无 | `POST /inspect/node-locators` |
| 选错了往上跳一级 | 有 | 无 | `ancestors` 祖先链 |
| locator 唯一性提示 | 有 | 无 | `matchCount` / `unique` |
| 节点全属性 | 有 | 只有 6 个字段 | `attrs` 原样给全 |

旧的 `snapshot` / `hit-test` 行为不变，前端可以逐步切。

---

## 一、接口

所有接口都在 `/devices/{deviceId}/inspect/` 下，权限沿用现有规则：GET 为 VIEWER，POST 为 TESTER。

### 1. `GET /devices/{id}/inspect/snapshot`（已有，未改）

截图并**冻结**当前控件树。后续 `hierarchy` / `node-locators` 默认都用这一刻的树，
保证「树 = 截图」，手机在这之后滚动了也不会错位。

### 2. `GET /devices/{id}/inspect/hierarchy?refresh=false`

返回整棵控件树。

| 参数 | 说明 |
|------|------|
| `refresh` | `false`（默认）用 snapshot 冻结的树，与截图严格对齐；`true` 强制重新 dump（树会和旧截图对不上，仅在「不重新截图但页面变了」时用） |

响应：

```jsonc
{
  "deviceId": "...",
  "platform": "ANDROID",
  "screenshotUrl": "devices/xxx/inspect/screenshot?t=1725430000000",
  "width": 1080, "height": 2400,          // 截图像素尺寸
  "hierarchyWidth": 1080, "hierarchyHeight": 2340,  // 控件树自身尺寸
  "scaleX": 1.0, "scaleY": 1.0,
  "nodeCount": 412,
  "refreshed": false,
  "root": { /* HierarchyNode，见下 */ }
}
```

`HierarchyNode`：

| 字段 | 说明 |
|------|------|
| `nodePath` | 树内唯一路径，如 `/hierarchy/node[1]/node[3]`。**取 locator、回选节点全靠它** |
| `xpath` | Appium 风格绝对 xpath（按 class 索引），等价 Inspector 里那一栏 |
| `tag` / `className` / `resourceId` / `text` / `contentDesc` / `packageName` | 常用属性，已做 Android / iOS 字段归一 |
| `index` / `depth` | 同级序号（1 起）/ 层级 |
| `clickable` `enabled` `displayed` `focused` `scrollable` `checkable` `checked` `selected` `password` `longClickable` | 布尔属性 |
| `bounds` | 控件树原始坐标 `{left,top,right,bottom,width,height}` |
| `screenBounds` | **已换算到截图像素坐标，前端画框直接用这个** |
| `attrs` | 该节点上的全部原始 XML 属性，右侧属性面板直接铺开 |
| `children` | 子节点数组 |

### 3. `POST /devices/{id}/inspect/node-locators`

取树上某个节点的候选 locator。

```jsonc
// 请求
{ "nodePath": "/hierarchy/node[1]/node[3]", "appId": "可选，用于匹配元素库" }
```

```jsonc
// 响应
{
  "nodePath": "...", "xpath": "...",
  "className": "...", "resourceId": "...", "text": "...", "contentDesc": "...",
  "clickable": true, "enabled": true,
  "bounds": {...}, "screenBounds": {...},
  "attrs": { /* 全部原始属性 */ },
  "ancestors": [ /* NodeRef，由近及远 */ ],
  "candidates": [ /* HitCandidate，与 hit-test 同构，可直接落库 */ ]
}
```

### 4. `POST /devices/{id}/inspect/hit-test`（已有，加了字段）

入参不变。响应新增：

| 新增字段 | 说明 |
|----------|------|
| `selectedNodePath` | 首选候选对应的树节点，**前端据此在树上高亮同一个节点** |
| `ancestors` | 由近及远的祖先链，用于「选错了往上跳一级」 |
| `hierarchyWidth` / `hierarchyHeight` | 控件树自身尺寸 |
| `candidates[].nodePath` | 该候选来自哪个节点（坐标方案为 `null`） |
| `candidates[].matchCount` | 该 locator 在当前树上命中几个元素；`-1` = 无法校验（如坐标方案） |
| `candidates[].unique` | `matchCount == 1`；无法校验时为 `null` |

---

## 二、坐标系（最容易踩的一处）

一共三套坐标，别混：

1. **截图像素**：`width` × `height`，即 `<img>` 的 `naturalWidth/naturalHeight`
2. **控件树坐标**：`hierarchyWidth` × `hierarchyHeight`，`node.bounds` 在这个坐标系里
3. **页面 CSS 像素**：`<img>` 在浏览器里的显示尺寸

规则：

- **画框只用 `node.screenBounds`**，它已经是截图像素坐标，只要再乘一次
  `img.clientWidth / img.naturalWidth` 就是 CSS 坐标，不要自己拿 `bounds` 去换算。
- **点选传 `x` / `y` 用截图像素**（`clientX` 换算回 `naturalWidth` 那套），
  优先传像素而不是 `xRatio/yRatio`，精度更高。
- Android 上截图常比 dump 高一截（底部手势条），后端按「同宽不缩放 + 底部夹紧」处理，
  这时 `scaleX/scaleY` 都是 `1.0`——**这是正常的，不要以为没换算**。

---

## 三、建议的双栏交互

```
┌────────────────┬──────────────────────┬───────────────┐
│  截图 + 边框    │   控件树（可折叠）      │   节点属性     │
│                │                      │   候选 locator │
└────────────────┴──────────────────────┴───────────────┘
```

1. 进页面 → `snapshot` → `hierarchy`，用 `root` 递归渲染树，用 `screenBounds` 在截图上叠框
2. hover 树节点 → 截图上高亮对应框；hover 截图 → 反查最小面积的命中节点并高亮树
3. 点截图 → 走 `hit-test` 拿推荐候选，同时用 `selectedNodePath` 在树上定位
4. 点树节点，或点 `ancestors` 里的某一级 → `node-locators` 取该节点的候选
5. 候选列表里对 `unique === false` 的标黄并显示「命中 N 个」，`unique === true` 标绿
6. 选中一条 → 走原有 `ui-elements` 落库流程

「往上跳一级」直接取 `ancestors[0].nodePath` 再调 `node-locators` 即可，不用重新点截图。

---

## 四、边界情况

- **根节点 / 零尺寸节点**（`<hierarchy>`、布局占位）在树上点得到，但不在命中集里。
  后端走降级分支，仍返回属性和绝对 xpath 候选，不会报「页面已变」。
- **`nodePath` 真的失效**（页面已经变了）：返回 400，提示重新刷新截图。
  前端遇到 400 应当自动重走 `snapshot` + `hierarchy`。
- **无法拿到控件树**（adb / WDA / hdc 异常）：返回 409，`message` 里有分平台的排查提示。
- **H5 / WebView 页面**：原生树里只能看到一层 WebView 壳，树也只有这么多。
  这种页面 `hit-test` 会把坐标方案提到最前，属预期行为。

---

## 五、和启发式的关系

`hit-test` 那套启发式**没有删**，它现在的定位是「给一个默认推荐」。
人不认可就在树上自己点——这意味着以后遇到坏 case，
第一反应应该是确认前端能不能点到正确节点，而不是再往后端加一条规则。
