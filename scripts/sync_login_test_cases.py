#!/usr/bin/env python3
"""同步「登录注册」发现页入口相关用例到安卓/iOS × ONLINE/TESTING。"""
from __future__ import annotations

import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:3000/api"


def req(method: str, path: str, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    r = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            j = json.loads(raw)
        except Exception:
            j = {"raw": raw[:800]}
        return e.code, j


def steps(*rows):
    out = []
    for i, (action, target, expected) in enumerate(rows, 1):
        out.append({"step": i, "action": action, "target": target, "expected": expected})
    return out


# —— 核心用例：按截图 1~7 补全（安卓/iOS 共用内容，平台差异在文案里微调）——

def core_cases(platform: str):
    other_login = (
        "可见「其他登录方式」及 Steam、Apple 入口"
        if platform == "IOS"
        else "可见「其他登录方式」及 Steam 入口"
    )
    return [
        {
            "caseId": "TC-001",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "发现页未登录引导条",
            "testPoint": "登录注册解锁更多功能 / 立即登录入口跳转",
            "title": "发现页点击「登录注册解锁更多功能/立即登录」进入手机号登录页",
            "priority": "P0",
            "type": "功能测试",
            "precondition": (
                "冷启动完美世界电竞 App，账号未登录；底部 Tab 进入「发现」首页；"
                "页面底部可见半透明引导条文案「登录注册解锁更多功能」及蓝色按钮「立即登录」"
            ),
            "stepsJson": json.dumps(
                steps(
                    (
                        "观察发现页底部登录引导区域",
                        "登录注册引导条",
                        "展示文案「登录注册解锁更多功能」，右侧展示蓝色「立即登录」按钮；引导条叠在内容列表之上、底栏之上",
                    ),
                    (
                        "点击引导条任意可点区域（文案区或「立即登录」按钮）",
                        "立即登录 / 登录注册解锁更多功能",
                        "跳转到手机号登录页（红橙 logo、关闭 X、手机号输入区）",
                    ),
                    (
                        "核对登录页默认态",
                        "登录页",
                        f"左上角关闭 X；默认区号 +86；占位「请输入你的手机号」；"
                        f"协议前为空心勾选圈（未勾选）；「发送验证码」按钮置灰不可点；"
                        f"辅助文案含「未注册的手机号验证后自动创建…」；{other_login}",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": (
                "从发现页引导条可进入登录页；登录页协议默认未勾选；"
                "未勾选且未填合法手机号时「发送验证码」保持置灰"
            ),
            "remark": "对应用例截图 1→2；安卓/iOS 入口文案一致",
            "automationAdvice": "RECOMMENDED",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-002",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "协议勾选",
            "testPoint": "空心圈 → 蓝勾",
            "title": "点击「我已阅读并同意」前复选圈可由空心变为蓝勾选中态",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "已进入手机号登录页，协议勾选圈为空心未勾选",
            "stepsJson": json.dumps(
                steps(
                    (
                        "确认协议前复选控件为未勾选空心圆圈",
                        "协议勾选框",
                        "显示为空心圈，无蓝色实心对勾",
                    ),
                    (
                        "点击空心圆圈（或「我已阅读并同意」可勾选热区）",
                        "协议勾选框",
                        "圆圈变为蓝色实心对勾（已勾选态）；协议文案与两个蓝色链接仍可见",
                    ),
                    (
                        "再次点击已勾选的复选圈",
                        "协议勾选框",
                        "恢复为空心未勾选态（可取消勾选）",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "协议勾选可在空心与蓝勾之间切换；勾选后不影响协议链接展示",
            "remark": "对应用例截图 4；自定义勾选控件以蓝勾视觉为准",
            "automationAdvice": "RECOMMENDED",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-003",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "协议链接",
            "testPoint": "用户协议页",
            "title": "点击《完美世界电竞用户协议》跳转协议页并可返回登录页",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "处于手机号登录页",
            "stepsJson": json.dumps(
                steps(
                    (
                        "点击协议文案中的蓝色链接《完美世界电竞用户协议》",
                        "用户协议链接",
                        "进入协议内容页，标题为「完美世界电竞用户协议」，可见更新日期与条款正文",
                    ),
                    (
                        "点击页面左上角返回",
                        "返回按钮",
                        "回到手机号登录页，输入与勾选状态保持进入协议前的状态",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "可打开用户协议页并返回登录页，状态不丢",
            "remark": "对应用例截图 5",
            "automationAdvice": "PARTIAL",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-004",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "协议链接",
            "testPoint": "个人信息保护政策页",
            "title": "点击《完美世界电竞个人信息保护政策》跳转政策页并可返回",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "处于手机号登录页",
            "stepsJson": json.dumps(
                steps(
                    (
                        "点击协议文案中的蓝色链接《完美世界电竞个人信息保护政策》",
                        "个人信息保护政策链接",
                        "进入政策内容页，标题为「完美世界电竞个人信息保护政策」，可见更新/生效时间与条款正文",
                    ),
                    (
                        "点击页面左上角返回",
                        "返回按钮",
                        "回到手机号登录页",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "可打开个人信息保护政策页并返回登录页",
            "remark": "对应用例截图 6",
            "automationAdvice": "PARTIAL",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-005",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "区号选择",
            "testPoint": "+86 下拉展开与切换",
            "title": "登录页点击 +86 展开区号列表并可切换非大陆区号",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "已从发现页进入手机号登录页，区号默认显示 +86",
            "stepsJson": json.dumps(
                steps(
                    (
                        "点击区号「+86」或右侧下拉箭头",
                        "+86 区号选择",
                        "弹出区号列表，至少可见：中国大陆 +86、中国台湾 +886、中国香港 +852、中国澳门 +853；当前项高亮",
                    ),
                    (
                        "点击「中国香港 +852」（或台湾/澳门任一非 +86 项）",
                        "区号列表项",
                        "列表收起；输入框左侧区号文案更新为所选值（如 +852），不再显示 +86",
                    ),
                    (
                        "再次点击区号，选回「中国大陆 +86」",
                        "区号选择",
                        "区号恢复为 +86",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "区号下拉可展开四项并成功切换；切回大陆后恢复 +86",
            "remark": "对应用例截图 3",
            "automationAdvice": "RECOMMENDED",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-006",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "发送验证码",
            "testPoint": "合法手机号+勾选协议后按钮点亮并跳转",
            "title": "输入合法手机号并勾选协议后「发送验证码」点亮，点击进入验证码页",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "处于登录页，网络正常，区号为 +86",
            "stepsJson": json.dumps(
                steps(
                    (
                        "点击「请输入你的手机号」输入框，输入合法 11 位号码（如 12312340001 或测试号）",
                        "手机号输入框",
                        "弹出数字键盘；号码正确回显；仅填号未勾协议时「发送验证码」仍置灰",
                    ),
                    (
                        "勾选「我已阅读并同意」前复选圈",
                        "协议勾选框",
                        "显示蓝色对勾；「发送验证码」按钮由浅色置灰变为深蓝可点击态（点亮）",
                    ),
                    (
                        "点击已点亮的「发送验证码」",
                        "发送验证码按钮",
                        "跳转至验证码输入页（发送至 +86 xxx、6 位验证码输入框、数字键盘）",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "勾选协议+合法手机号后发送验证码点亮；点击后进入验证码页",
            "remark": "对应用例截图 2 输入 + 截图 7 点亮；点亮条件：协议已勾选且手机号合法",
            "automationAdvice": "RECOMMENDED",
            "status": "REVIEWED",
        },
        {
            "caseId": "TC-007",
            "pageName": "登录页",
            "module": "登录注册",
            "subModule": "登录入口",
            "testItem": "验证码登录成功",
            "testPoint": "正确验证码登录回发现页",
            "title": "输入正确短信验证码后登录成功回到发现页且引导条消失",
            "priority": "P0",
            "type": "功能测试",
            "precondition": "已完成「发送验证码」进入验证码页，手机可收到正确 6 位短信验证码",
            "stepsJson": json.dumps(
                steps(
                    (
                        "在验证码页依次输入正确的 6 位短信验证码",
                        "验证码输入框",
                        "6 位数字依次填入；输完后自动校验或完成提交",
                    ),
                    (
                        "等待登录完成回到主界面",
                        "发现页",
                        "回到发现首页；底部不再显示「登录注册解锁更多功能 / 立即登录」引导条",
                    ),
                    (
                        "点击底部「我的」Tab 查看登录态（可选核对）",
                        "我的",
                        "展示已登录账号信息（昵称/头像等），非未登录引导态",
                    ),
                ),
                ensure_ascii=False,
            ),
            "expectedResult": "验证码正确则登录成功，发现页登录引导条消失，账号已登录",
            "remark": "闭环：截图 1 入口 → 登录 → 回发现页",
            "automationAdvice": "PARTIAL",
            "status": "REVIEWED",
        },
    ]


EXTRA_ANDROID_IDS = list(range(45, 58))  # unused legacy marker


def list_cases(category: str, stage: str):
    code, data = req(
        "GET",
        f"/ai-test-cases?category={category}&releaseStage={stage}&page=1&size=100",
    )
    if code != 200:
        raise RuntimeError(f"list failed {category}/{stage}: {code} {data}")
    return data.get("content") or []


def upsert_core(category: str, platform: str, stage: str):
    existing = {c.get("caseId"): c for c in list_cases(category, stage)}
    created, updated = 0, 0
    for case in core_cases(platform):
        body = {
            **case,
            "category": category,
            "platform": platform,
            "releaseStage": stage,
            "source": "MANUAL",
        }
        old = existing.get(case["caseId"])
        if old and old.get("id"):
            # 更新：不强制改 caseId
            patch = {k: v for k, v in body.items() if k != "caseId"}
            code, data = req("PUT", f"/ai-test-cases/{old['id']}", patch)
            if code != 200:
                raise RuntimeError(f"update {category}/{stage}/{case['caseId']}: {code} {data}")
            updated += 1
            print(f"  UPDATE {category}/{stage} {case['caseId']} id={old['id']}")
        else:
            code, data = req("POST", "/ai-test-cases", body)
            if code not in (200, 201):
                raise RuntimeError(f"create {category}/{stage}/{case['caseId']}: {code} {data}")
            created += 1
            print(f"  CREATE {category}/{stage} {case['caseId']} id={data.get('id')}")
    return created, updated


def sync_remaining_android_testing():
    """把安卓 ONLINE 中非核心（旧 TC-008~020，现仍存库）复制到 TESTING，若 TESTING 尚无。"""
    online = list_cases("wmpvp-android", "ONLINE")
    testing = {c.get("caseId"): c for c in list_cases("wmpvp-android", "TESTING")}
    core_ids = {c["caseId"] for c in core_cases("ANDROID")}
    created = 0
    for c in online:
        cid = c.get("caseId")
        if cid in core_ids:
            continue  # 已由 upsert_core 处理
        if cid in testing:
            continue
        body = {
            "caseId": cid,
            "pageName": c.get("pageName") or "登录页",
            "module": c.get("module") or "登录注册",
            "subModule": c.get("subModule") or "登录入口",
            "testItem": c.get("testItem"),
            "testPoint": c.get("testPoint"),
            "title": c.get("title"),
            "priority": c.get("priority") or "P1",
            "type": c.get("type") or "功能测试",
            "precondition": c.get("precondition"),
            "stepsJson": json.dumps(c.get("steps") or [], ensure_ascii=False),
            "expectedResult": c.get("expectedResult"),
            "remark": c.get("remark"),
            "automationAdvice": c.get("automationAdvice"),
            "status": c.get("status") or "REVIEWED",
            "category": "wmpvp-android",
            "platform": "ANDROID",
            "releaseStage": "TESTING",
            "source": "MANUAL",
        }
        code, data = req("POST", "/ai-test-cases", body)
        if code not in (200, 201):
            print(f"  WARN copy {cid} -> TESTING: {code} {data}")
            continue
        created += 1
        print(f"  COPY ONLINE->{stage} skip" if False else f"  COPY ONLINE->TESTING {cid}")
    return created


def sync_ios_extras_from_android():
    """iOS 同步安卓 ONLINE 非核心异常/其他登录用例（标题与步骤相同，平台 IOS）。"""
    online = list_cases("wmpvp-android", "ONLINE")
    core_ids = {c["caseId"] for c in core_cases("ANDROID")}
    created_total = 0
    for stage in ("ONLINE", "TESTING"):
        existing = {c.get("caseId"): c for c in list_cases("wmpvp-ios", stage)}
        for c in online:
            cid = c.get("caseId")
            if cid in core_ids:
                continue
            if cid in existing:
                continue
            title = c.get("title") or ""
            # Steam 文案在 iOS 可保留；截图另有 Apple，在 remark 注明
            remark = (c.get("remark") or "") + "；iOS 另可见 Apple 登录入口"
            body = {
                "caseId": cid,
                "pageName": c.get("pageName") or "登录页",
                "module": c.get("module") or "登录注册",
                "subModule": c.get("subModule") or "登录入口",
                "testItem": c.get("testItem"),
                "testPoint": c.get("testPoint"),
                "title": title,
                "priority": c.get("priority") or "P1",
                "type": c.get("type") or "功能测试",
                "precondition": c.get("precondition"),
                "stepsJson": json.dumps(c.get("steps") or [], ensure_ascii=False),
                "expectedResult": c.get("expectedResult"),
                "remark": remark.strip("；"),
                "automationAdvice": c.get("automationAdvice"),
                "status": c.get("status") or "REVIEWED",
                "category": "wmpvp-ios",
                "platform": "IOS",
                "releaseStage": stage,
                "source": "MANUAL",
            }
            code, data = req("POST", "/ai-test-cases", body)
            if code not in (200, 201):
                print(f"  WARN ios {stage} {cid}: {code} {data}")
                continue
            created_total += 1
            print(f"  CREATE iOS/{stage} {cid}")
    return created_total


def renumber_conflict_note():
    """安卓 ONLINE 旧 TC-008 与新核心 TC-008 冲突：把旧「Steam」及后续顺延改号。"""
    online = list_cases("wmpvp-android", "ONLINE")
    by_id = {c["caseId"]: c for c in online}
    # 旧库：TC-008 Steam ... TC-020 关闭
    # 新核心占用 TC-001~008；将旧 TC-008~020 改为 TC-009~021
    mapping = {}
    for n in range(20, 7, -1):  # 20..8 倒序避免覆盖
        old = f"TC-{n:03d}"
        new = f"TC-{n+1:03d}"
        if old in by_id:
            mapping[old] = new
    # 仅当旧 TC-008 标题仍是 Steam 时需要改号
    steam = by_id.get("TC-008")
    if not steam:
        return 0
    title = steam.get("title") or ""
    if "验证码" in title and "Steam" not in title:
        # 已被核心用例覆盖，无需改号
        return 0
    changed = 0
    for old, new in mapping.items():
        c = by_id.get(old)
        if not c:
            continue
        if new in by_id and new != old:
            # 目标号已存在则跳过，避免踩踏
            print(f"  SKIP renumber {old}->{new} (target exists)")
            continue
        code, data = req("PUT", f"/ai-test-cases/{c['id']}", {"caseId": new})
        # updateManual 可能不支持只改 caseId — 检查服务
        print(f"  RENUMBER try {old}->{new}: {code}")
        if code == 200:
            changed += 1
    return changed


def main():
    print("1) upsert core ANDROID ONLINE/TESTING")
    for stage in ("ONLINE", "TESTING"):
        c, u = upsert_core("wmpvp-android", "ANDROID", stage)
        print(f"   -> {stage}: created={c} updated={u}")

    print("2) upsert core IOS ONLINE/TESTING")
    for stage in ("ONLINE", "TESTING"):
        c, u = upsert_core("wmpvp-ios", "IOS", stage)
        print(f"   -> {stage}: created={c} updated={u}")

    print("3) copy remaining ANDROID ONLINE extras -> TESTING")
    n = sync_remaining_android_testing()
    print(f"   -> copied {n}")

    print("4) copy ANDROID extras -> IOS ONLINE/TESTING")
    n = sync_ios_extras_from_android()
    print(f"   -> created {n}")

    print("5) summary counts")
    for cat in ("wmpvp-android", "wmpvp-ios"):
        for stage in ("ONLINE", "TESTING"):
            items = list_cases(cat, stage)
            print(f"   {cat}/{stage}: {len(items)}")
            for c in items[:8]:
                print(f"      {c.get('caseId')} {c.get('title')[:50]}")


if __name__ == "__main__":
    main()
