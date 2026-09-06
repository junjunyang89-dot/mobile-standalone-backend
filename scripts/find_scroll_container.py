#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
找「该绑哪个元素」——从当前屏的控件树里，定位一段文案所在的可滑动容器。

用在这种场景：想滑首页金刚位那一条，结果整屏一滑就切到了别的游戏页签。
原因几乎总是同一个：**页面上有好几层可横滑的东西套在一起**，绑错了一层就等于整屏滑。

    最外层：CS / DOTA2 / DEADLOCK 的页签 ViewPager   ← 绑到这层就会切页签
      └─ 金刚位那一条（要滑的是它）
      └─ 资讯 / 动态 / 热评 / 视频 / 活动 的页签

用法（先在手机上手动走到目标页面，别让它跑掉）：

    python3 scripts/find_scroll_container.py \\
        --base http://127.0.0.1:3000/api \\
        --device <deviceId> \\
        --token <登录后的 Bearer token> \\
        --text 完美开箱

    # 不给 --text 就列出当前屏所有可滑动容器，按「最像横滑条」排序
    python3 scripts/find_scroll_container.py --base ... --device ... --token ...

输出里 ✅ 标的那个就是你要绑的元素；直接把它的 resource-id 或 xpath 录进元素库。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request


def http_get(url, token):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("X-Auth-Token", token)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:300]
        print(f"❌ HTTP {e.code} {url}\n   {body}")
        sys.exit(2)
    except Exception as e:
        print(f"❌ 请求失败 {url}: {e}")
        sys.exit(2)


def walk(node, path, out):
    """深度优先，记录每个节点和它的祖先链"""
    if not node:
        return
    out.append((node, list(path)))
    path.append(node)
    for c in node.get("children") or []:
        walk(c, path, out)
    path.pop()


def box(node):
    b = node.get("screenBounds") or node.get("bounds") or {}
    return (
        int(b.get("left", 0)),
        int(b.get("top", 0)),
        int(b.get("width", 0)),
        int(b.get("height", 0)),
    )


def label(node):
    return node.get("text") or node.get("contentDesc") or ""


def describe(node, screen_w, screen_h, mark=""):
    left, top, w, h = box(node)
    rid = (node.get("resourceId") or "").split("/")[-1]
    wide = w >= screen_w * 0.8
    short = h <= screen_h * 0.25
    shape = "横滑条" if (wide and short) else ("整屏/大块" if (wide and not short) else "小块")
    return (
        f"{mark}{shape:<8} {w}×{h} @({left},{top})  "
        f"id={rid or '(无)'}  class={(node.get('className') or '').split('.')[-1]}\n"
        f"          xpath={node.get('xpath')}"
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="后端地址，含 /api，例 http://127.0.0.1:3000/api")
    ap.add_argument("--device", required=True)
    ap.add_argument("--token", default="", help="登录后的 token；后端未开鉴权可不填")
    ap.add_argument("--text", default="", help="目标文案，例：完美开箱")
    ap.add_argument("--no-snapshot", action="store_true", help="不重新截图，用上次冻结的树")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    if not args.no_snapshot:
        http_get(f"{base}/devices/{args.device}/inspect/snapshot", args.token)
    data = http_get(f"{base}/devices/{args.device}/inspect/hierarchy", args.token)

    screen_w = int(data.get("width") or 1080)
    screen_h = int(data.get("height") or 1920)
    root = data.get("root")
    if not root:
        print("❌ 控件树为空")
        sys.exit(1)

    nodes = []
    walk(root, [], nodes)
    print(f"屏幕 {screen_w}×{screen_h}，共 {len(nodes)} 个节点\n")

    if args.text:
        hits = [(n, anc) for n, anc in nodes if args.text in label(n)]
        if not hits:
            print(f"❌ 当前屏没找到文案「{args.text}」——确认手机停在目标页面了吗？")
            sys.exit(1)
        node, ancestors = hits[0]
        print(f"找到「{args.text}」：{describe(node, screen_w, screen_h)}\n")
        print("往上找可滑动的祖先（从近到远）：\n")
        found = False
        for anc in reversed(ancestors):
            if not anc.get("scrollable"):
                continue
            mark = "✅ 绑这个 → " if not found else "   （更外层，绑它就会滑错对象）"
            print(describe(anc, screen_w, screen_h, mark))
            print()
            found = True
        if not found:
            print("⚠️  这条链上没有 scrollable=true 的祖先。")
            print("   要么这一条其实滑不动，要么它的可滑动性没体现在控件树上——")
            print("   后一种情况可以退而求其次：绑住这一条的直接父容器（下面那个「横滑条」形状的），")
            print("   再把「区域内滑动」的两端留白调大到 25~30。")
            for anc in reversed(ancestors[-4:]):
                print("   候选: " + describe(anc, screen_w, screen_h))
        return

    print("当前屏所有可滑动容器（按最像横滑条排序）：\n")
    cands = [n for n, _ in nodes if n.get("scrollable")]
    if not cands:
        print("（一个都没有）")
        return

    def score(n):
        _, _, w, h = box(n)
        wide = w >= screen_w * 0.8
        short = h <= screen_h * 0.25
        return (0 if (wide and short) else 1, h)

    for n in sorted(cands, key=score):
        print(describe(n, screen_w, screen_h))
        print()


if __name__ == "__main__":
    main()
