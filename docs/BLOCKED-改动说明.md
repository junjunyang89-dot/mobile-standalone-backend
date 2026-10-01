# 执行结果加 BLOCKED：「没跑成」不再算「失败」

> 一句话：**断言跑了没过 = FAILED；根本没跑到断言 = BLOCKED。**
> BLOCKED 不进通过率的分母,但会单独报出来。

## 为什么

我们这几天修的每一个问题——滑动超时、Appium 会话失效、控件树抓不到、应用没在前台——**全都是「没跑成」,一个「用例失败」都没有**。但报告里它们和「这个按钮真的坏了」长得一模一样,混在同一个通过率里。

后果不是少了个字段,是**通过率在说谎**:模拟器卡了一晚上,早上看到「通过率 40%」,人会去查用例、查定位、查应用,而那 60% 压根没跑到断言那一步。

接口侧同理:服务挂了,200 条用例全红,报告上是「200 条用例失败」,看起来像 200 个 bug,实际是 1 个环境故障。

---

## 判定规则

**方向性很重要**:误判成 FAILED 最多让人多查一会儿;**误判成 BLOCKED 会把一个真实的产品缺陷从通过率里摘出去、藏起来**——那比现在更糟。

所以判定一律**偏保守,拿不准就算 FAILED**。

### App 侧(`ExecutionBlockers`)

两道:

1. **源头标记**(准)——抛异常的那一刻最清楚这是哪一种,`UiFlowExecutionException.blocked(...)` 直接标
2. **文案兜底**(补)——从驱动层直接冒上来的异常,按平台自己写的文案匹配

短语表只收**平台自己抛的文案**,不收第三方库的——第三方措辞随版本变,拿它做判定条件,升级一次库就悄悄失效,而且没人会发现。

| 算 BLOCKED | 不算(还是 FAILED) |
| --- | --- |
| 设备不存在 / 未连接 / 被占用 / 排队超时 | 点击失败、断言未通过 |
| Appium 会话失效、挂接失败 | **元素找不到** ← 这正是 UI 回归要抓的 |
| 控件树抓不到 | 滑动失败 |
| 装包失败 | |
| 包名没配、应用字典缺失 | |

### 接口侧

按**异常类型**判,不按文案——这几类是 JDK 抛的,类型稳定:

```java
ConnectException / UnknownHostException / SocketTimeoutException
NoRouteToHostException / PortUnreachableException
HttpTimeoutException / SSLException
```

**笼统的 `IOException` 不算**——响应体读到一半断了也是它,但那时请求已经发出去、服务也答了。把它算成「没跑成」会把一类真实的服务端问题藏起来。

---

## 改了哪些地方

| 位置 | 改动 |
| --- | --- |
| `Task.TaskStatus` / `TaskExecution.ExecutionStatus` | `+= BLOCKED` |
| `OnlineRegressionStep.Status` | `+= BLOCKED` |
| `ApiTestRun` / `api_test_runs` | `+= blocked` 列(`ensureColumn` 自动加,不用跑 SQL) |
| `ApiTestDtos.RunResult` | `+= blocked` |
| `service/ExecutionBlockers.java` | **新增**,判定逻辑 |
| `UiFlowExecutionException` | `+= blocked` 标记 + `blocked()` 工厂 |
| `TaskService` | catch 里按分类落 BLOCKED |
| `DeviceTaskScheduler` | `failTask` → `blockTask`,排队超时/取不到应用都记 BLOCKED |
| `OnlineRegressionOrchestrator` | `waitTask` 认 BLOCKED 为终态;step 落 BLOCKED;通过率分母排除;**有 blocked 就告警** |
| `ApiTestRunnerService` | 网络类异常 → BLOCKED;用例不存在 → BLOCKED;`ok = failed==0 && blocked==0` |
| `ApiTestRunMapper.xml` | insert 带 blocked;趋势统计单独聚合 blocked |

### 三处值得单说

**1. `waitTask` 漏了 BLOCKED 会挂**

线上回归等任务终态原来只认 `COMPLETED/FAILED/STOPPED`。加了 BLOCKED 不同步改的话,**一台连不上的设备能把一轮回归空等十几分钟**。已经加上了。

**2. BLOCKED 也要告警**

把 BLOCKED 从通过率里摘出去,是为了**别冤枉用例**,不是为了让它安静消失。夜里设备掉线,第二天看到漂亮的 100%、实际一条没跑——这比通过率难看严重得多。所以 `shouldAlertOnlineRegression` 加了一条:**blocked > 0 就推送**。

**3. `ok` 也要求 blocked=0**

接口侧 `ok` 是给上线卡点用的。**「没有证据」和「证据说没问题」是两回事**,所以没跑成也不能算 ok。

---

## 验证

- **447 个源文件真实编译通过**(本机 Maven 仓库 + Lombok),0 错误
- **新增 `ExecutionBlockersTest`,13 个用例全过**。其中一半是**反向**的:断言失败、元素找不到、笼统 IOException **都不能**被判成 BLOCKED
- **确认这组测试真能挡住误判**:往短语表里故意塞一条太宽的「失败」,立刻有 2 条测试变红(`断言失败不是没跑成`、`元素找不到是回归要抓的东西不能藏`)。这正是这组测试存在的理由——防的是**以后有人顺手加一条太宽的词**
- **全量 1245 个单测**:1203 通过、20 跳过、12 失败——12 个还是缺 `/doc/app-8999.docx` 和需要网络的那几个,改动前后一致

**没验到的**:真的跑一轮带环境故障的回归,看报告长什么样。这个得你来——**建议故意把模拟器关掉跑一次线上回归**,应该看到通过率不掉、blocked 计数出现、微信收到告警。

---

## 前端要改吗

后端不改也能跑,但报告上 BLOCKED 会显示成未知状态。建议加三处:

1. **状态色**:BLOCKED 用**灰色 / 橙色**,别用红——它不是失败
2. **通过率旁边加一个数**:`通过率 92%(3 条没跑成)`,点进去能看是哪几条
3. **接口执行历史**:`blocked` 列已经在 `findRuns` 的返回里了,直接读

不用跑 SQL(`ensureColumn` 会自动加列)。**后端要重启。**
