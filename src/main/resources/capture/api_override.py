# -*- coding: utf-8 -*-
"""接口响应改写：把「另一半分支」变成可测的。

**为什么需要它**

App 里有一批服务端下发的开关（`myTabSysWebSwitch` 决定「我的」页面用哪个 WebView
内核，还有 6 个别的）。测试跑的永远是服务端当前那个值，**另一半分支从来没被执行过**。
这比白屏更隐蔽：白屏至少看得见，没走过的分支是一段压根没运行过的代码。

改服务端配置不行——那是全局的，不是按用例的。直接改手机上的 SharedPreferences 也不行：
进程内存里有缓存，改文件不生效；杀进程再改，启动后又会被服务端配置盖掉。

所以只剩一条路：**在代理层改写那个接口的响应**。平台的抓包已经是 mitmproxy 走
`adb reverse`，debug 包也已经信任用户证书、没有证书固定——链路是现成的。

它还顺带解决另一大类问题：**故障注入**。让某个接口超时 / 返回 500 / 返回空列表，
就能测 App 的兜底路径——那同样是一段几乎没被执行过的代码。

**纯函数，不碰 mitmproxy**，这样每条规则都能用单测钉住。而这里最该钉住的不是
「改对了没有」，是下面这条。

---

**最危险的失败方式：改不到却当作改了**

如果规则指向的字段不存在（接口改版、路径写错），而我们静默放过——
整批用例会以为自己在测 B 分支，实际还在测 A 分支，**而且全绿**。
这比不改写糟得多：它生产的是一个错误的结论，没有任何迹象。

所以：**一条规则只要有一处改不到，这条规则整条不生效，并且必须报出来。**
部分生效是最坏的结果——响应被改成了一个既不是 A 也不是 B 的形状。
"""
import copy
import json
import re
import time

# 规则文件的过期保护。忘了关的改写规则会污染之后所有的测试结果，
# 而那种污染完全没有迹象——人只会看到一批莫名其妙的失败，
# 查上半天才想起来代理上还挂着东西
DEFAULT_TTL_SECONDS = 2 * 60 * 60

_INDEX = re.compile(r"^(.*?)\[(\d+)]$")


class OverrideResult(object):
    """改写结果。

    :param applied: 生效了的规则 id。**必须留痕**——人看到一个诡异的响应时，
                    第一个该能回答的问题是「这是不是平台改的」
    :param problems: 没生效的原因。调用方要把它变成显眼的东西，不能只写日志
    :param delay_ms: 要故意拖多久（故障注入）
    """

    def __init__(self, status, body, applied=None, problems=None, delay_ms=0):
        self.status = status
        self.body = body
        self.applied = applied or []
        self.problems = problems or []
        self.delay_ms = delay_ms

    @property
    def changed(self):
        return bool(self.applied)


def load_rules(path, now=None):
    """读规则文件。读不到、过期、格式不对 —— 一律当成「没有规则」，但把原因带回去。

    绝不能因为规则文件有问题就让抓包挂掉：抓包是主业，改写是附加功能。
    """
    problems = []
    try:
        with open(path, "r", encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        return [], problems
    except Exception as e:  # noqa: BLE001
        return [], ["规则文件读不了（%s），本次不改写任何响应" % e]

    now = now if now is not None else time.time()
    expires = doc.get("expiresAt")
    if expires is None:
        expires = now + DEFAULT_TTL_SECONDS
    if now > expires:
        return [], ["改写规则已过期（%s），本次不改写任何响应——"
                    "过期保护是为了防止忘了关的规则污染后面所有测试" % _fmt(expires)]

    rules = doc.get("rules") or []
    good = []
    for i, r in enumerate(rules):
        rid = r.get("id")
        if not rid:
            problems.append("第 %d 条规则没有 id，跳过——没有 id 就没法留痕" % (i + 1))
            continue
        if not r.get("match"):
            problems.append("规则「%s」没有 match，跳过" % rid)
            continue
        good.append(r)
    return good, problems


def matches(rule, method, host, path):
    """规则命不命中这个请求。

    path 用**包含**匹配而不是全等：真实路径常带版本前缀（`/api/v2/getAppConfigs`），
    要求全等的话每个人都得先去抓一次包才会写规则。
    method 和 host 留空＝不限制。
    """
    m = rule.get("match") or {}
    want_method = (m.get("method") or "").strip().upper()
    if want_method and want_method != (method or "").upper():
        return False
    want_host = (m.get("host") or "").strip().lower()
    if want_host:
        h = (host or "").lower()
        if h != want_host and not h.endswith("." + want_host):
            return False
    want_path = m.get("path") or ""
    if want_path and want_path not in (path or ""):
        return False
    return True


def apply_overrides(rules, method, host, path, status, body_text):
    """挨条应用。

    一条规则有任何一处改不到，**这条整条不生效**并记 problem。
    部分生效会把响应改成一个既不是原样也不是目标的形状，
    而测试会以为自己测的是目标分支。
    """
    applied = []
    problems = []
    delay_ms = 0
    cur_status = status
    cur_body = body_text

    for rule in rules:
        if not matches(rule, method, host, path):
            continue
        rid = rule.get("id")

        delay = int(rule.get("delayMs") or 0)
        if delay > 0:
            delay_ms = max(delay_ms, delay)

        new_status = rule.get("status")
        sets = rule.get("set") or {}
        body_override = rule.get("body")

        if body_override is not None:
            cur_body = body_override if isinstance(body_override, str) \
                else json.dumps(body_override, ensure_ascii=False)
            if new_status is not None:
                cur_status = int(new_status)
            applied.append(rid)
            continue

        if sets:
            try:
                doc = json.loads(cur_body) if cur_body else None
            except Exception:  # noqa: BLE001
                problems.append("规则「%s」要改 JSON 字段，但这个响应不是 JSON，整条未生效" % rid)
                continue
            if doc is None:
                problems.append("规则「%s」要改 JSON 字段，但响应体是空的，整条未生效" % rid)
                continue

            # 先在副本上全部试一遍，全成功才落回去。
            #
            # 说句老实话：就当前的控制流而言这个 deepcopy 是<b>多余的</b>——
            # 下面失败时走的是 continue，压根不会把改动写回 cur_body，
            # 所以即使直接在 doc 上改，返回的响应体也还是原样。
            # 我把它留着，是因为一旦有人把控制流改成「边改边写回」，
            # 原子性就会在没有任何报错的情况下丢掉。
            # 但它<b>没有被测试覆盖到</b>（把它去掉，22 条测试一条都不会红），
            # 所以别把它当成一道验证过的防线。
            draft = copy.deepcopy(doc)
            missed = []
            for jpath, value in sets.items():
                ok = _set_path(draft, jpath, value, allow_create=bool(rule.get("create")))
                if not ok:
                    missed.append(jpath)
            if missed:
                problems.append(
                    "规则「%s」在响应里找不到这些字段：%s —— 整条未生效。"
                    "多半是接口结构变了或路径写错了。"
                    "（不静默放过是有意的：放过的话这批用例会以为自己在测另一个分支，而且全绿）"
                    % (rid, "、".join(missed)))
                continue
            cur_body = json.dumps(draft, ensure_ascii=False)

        if new_status is not None:
            cur_status = int(new_status)
        if sets or new_status is not None or delay > 0:
            applied.append(rid)

    return OverrideResult(cur_status, cur_body, applied, problems, delay_ms)


def _set_path(doc, jpath, value, allow_create=False):
    """按 `a.b[0].c` 定位并赋值。定位不到返回 False —— **不创建**。

    默认不创建是有意的：App 只读它已知的那些字段，凭空造一个新字段它根本不会看，
    但规则会显示「已生效」。那就是一次静默的空操作，
    而空操作被当成生效，正是这里最想防的事。
    """
    parts = [p for p in str(jpath).split(".") if p != ""]
    if not parts:
        return False
    cur = doc
    for p in parts[:-1]:
        cur = _descend(cur, p, create=allow_create)
        if cur is None:
            return False
    last = parts[-1]
    m = _INDEX.match(last)
    if m:
        name, idx = m.group(1), int(m.group(2))
        holder = cur if name == "" else _descend(cur, name, create=allow_create)
        if not isinstance(holder, list) or idx >= len(holder):
            return False
        holder[idx] = value
        return True
    if isinstance(cur, dict):
        if last not in cur and not allow_create:
            return False
        cur[last] = value
        return True
    return False


def _descend(node, part, create=False):
    m = _INDEX.match(part)
    if m:
        name, idx = m.group(1), int(m.group(2))
        holder = node if name == "" else _descend(node, name, create=create)
        if not isinstance(holder, list) or idx >= len(holder):
            return None
        return holder[idx]
    if isinstance(node, dict):
        if part in node:
            return node[part]
        if create:
            node[part] = {}
            return node[part]
    return None


def _fmt(ts):
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(float(ts)))
    except Exception:  # noqa: BLE001
        return str(ts)
