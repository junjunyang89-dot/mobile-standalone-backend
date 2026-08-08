#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
鸿蒙 UI 旁路：基于 hmdriver2（底层 hdc，不用 adb）。
Java HmdriverBridge 以子进程调用，stdout 最后一行输出 JSON。
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from typing import Any, Dict, Optional


def _out(ok: bool, **extra: Any) -> None:
    payload: Dict[str, Any] = {"ok": ok}
    payload.update(extra)
    print(json.dumps(payload, ensure_ascii=False))


def _parse_selector(selector: str) -> Dict[str, Any]:
    s = (selector or "").strip()
    if not s:
        raise ValueError("empty selector")
    if s.startswith("text:"):
        return {"text": s[5:]}
    if s.startswith("id:"):
        return {"id": s[3:]}
    if s.startswith("resource-id:"):
        return {"id": s[len("resource-id:") :]}
    if s.startswith("desc:") or s.startswith("description:"):
        key = "desc:" if s.startswith("desc:") else "description:"
        return {"description": s[len(key) :]}
    if s.startswith("key:"):
        return {"key": s[4:]}
    if s.startswith("type:"):
        return {"type": s[5:]}
    if s.startswith("xpath:"):
        return {"_xpath": s[6:]}
    # 默认按文案
    return {"text": s}


def _driver(serial: Optional[str]):
    from hmdriver2.driver import Driver

    return Driver(serial) if serial else Driver()


def _find(d, selector: str):
    kw = _parse_selector(selector)
    if "_xpath" in kw:
        return d.xpath(kw["_xpath"])
    return d(**kw)


def cmd_ping(_args: argparse.Namespace) -> None:
    try:
        import hmdriver2  # noqa: F401
        from hmdriver2.driver import list_devices  # noqa: F401

        _out(True, version=getattr(hmdriver2, "__version__", "unknown"))
    except Exception as e:
        _out(False, error=str(e))


def cmd_list_devices(_args: argparse.Namespace) -> None:
    from hmdriver2.driver import list_devices

    devices = list_devices()
    _out(True, devices=devices)


def cmd_device_info(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    info = d.device_info
    _out(
        True,
        productName=getattr(info, "productName", None),
        model=getattr(info, "model", None),
        sdkVersion=getattr(info, "sdkVersion", None),
        sysVersion=getattr(info, "sysVersion", None),
    )


def cmd_click(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    d.click(args.x, args.y)
    _out(True)


def cmd_click_element(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    obj = _find(d, args.selector)
    if hasattr(obj, "click_if_exists"):
        # UiObject
        if not obj.exists(retries=2, wait_time=0.5):
            _out(False, error="element not found", selector=args.selector)
            return
        obj.click()
    else:
        # xpath result
        obj.click()
    _out(True)


def cmd_swipe(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    # hmdriver2 swipe 用 speed；duration 粗略映射
    speed = 2000
    if args.duration and args.duration > 0:
        dist = abs(args.x2 - args.x1) + abs(args.y2 - args.y1)
        speed = max(200, int(dist / (args.duration / 1000.0))) if args.duration else 2000
    d.swipe(args.x1, args.y1, args.x2, args.y2, speed=speed)
    _out(True)


def cmd_input_text(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    d.input_text(args.text)
    _out(True)


def cmd_input_element(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    obj = _find(d, args.selector)
    if hasattr(obj, "input_text"):
        obj.input_text(args.text)
    else:
        obj.click()
        time.sleep(0.3)
        d.input_text(args.text)
    _out(True)


def cmd_screenshot(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    path = d.screenshot(args.path)
    _out(True, path=path or args.path)


def cmd_dump(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    h = d.dump_hierarchy()
    # 直接塞对象，避免二次 json.dumps 字符串再被 Java 解析踩坑
    _out(True, hierarchy=h if isinstance(h, (dict, list)) else str(h))


def cmd_wait_element(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    deadline = time.time() + max(1, args.timeout)
    last_err = None
    while time.time() < deadline:
        try:
            obj = _find(d, args.selector)
            if hasattr(obj, "exists") and obj.exists(retries=1, wait_time=0.5):
                _out(True)
                return
            if hasattr(obj, "get") or hasattr(obj, "click"):
                # xpath：尝试访问
                try:
                    _ = obj.get() if hasattr(obj, "get") else True
                    _out(True)
                    return
                except Exception as e:
                    last_err = str(e)
        except Exception as e:
            last_err = str(e)
        time.sleep(0.4)
    _out(False, error=last_err or "timeout", selector=args.selector)


def cmd_exists(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    try:
        obj = _find(d, args.selector)
        exists = bool(obj.exists(retries=1, wait_time=0.5)) if hasattr(obj, "exists") else False
        _out(True, exists=exists)
    except Exception as e:
        _out(True, exists=False, error=str(e))


def cmd_start_app(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    if args.ability:
        d.start_app(args.package, args.ability)
    else:
        d.start_app(args.package)
    _out(True)


def cmd_stop_app(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    d.stop_app(args.package)
    _out(True)


def cmd_list_apps(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    apps = d.list_apps()
    _out(True, apps=list(apps or []))


def cmd_install(args: argparse.Namespace) -> None:
    d = _driver(args.serial)
    d.install_app(args.path)
    _out(True)


def main() -> int:
    # 避免把 hmdriver2 DEBUG 打进 stdout，干扰 JSON
    try:
        import logging

        logging.getLogger("hmdriver2").setLevel(logging.WARNING)
        logging.getLogger().setLevel(logging.WARNING)
    except Exception:
        pass
    parser = argparse.ArgumentParser(description="hmdriver2 bridge for mobile-standalone")
    parser.add_argument("--serial", default=None, help="hdc target serial")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("ping")
    sub.add_parser("list-devices")
    sub.add_parser("device-info")

    p = sub.add_parser("click")
    p.add_argument("--x", type=int, required=True)
    p.add_argument("--y", type=int, required=True)

    p = sub.add_parser("click-element")
    p.add_argument("--selector", required=True)

    p = sub.add_parser("swipe")
    p.add_argument("--x1", type=int, required=True)
    p.add_argument("--y1", type=int, required=True)
    p.add_argument("--x2", type=int, required=True)
    p.add_argument("--y2", type=int, required=True)
    p.add_argument("--duration", type=int, default=300)

    p = sub.add_parser("input-text")
    p.add_argument("--text", required=True)

    p = sub.add_parser("input-element")
    p.add_argument("--selector", required=True)
    p.add_argument("--text", required=True)

    p = sub.add_parser("screenshot")
    p.add_argument("--path", required=True)

    sub.add_parser("dump")

    p = sub.add_parser("wait-element")
    p.add_argument("--selector", required=True)
    p.add_argument("--timeout", type=int, default=10)

    p = sub.add_parser("exists")
    p.add_argument("--selector", required=True)

    p = sub.add_parser("start-app")
    p.add_argument("--package", required=True)
    p.add_argument("--ability", default=None)

    p = sub.add_parser("stop-app")
    p.add_argument("--package", required=True)

    sub.add_parser("list-apps")

    p = sub.add_parser("install")
    p.add_argument("--path", required=True)

    args = parser.parse_args()
    handlers = {
        "ping": cmd_ping,
        "list-devices": cmd_list_devices,
        "device-info": cmd_device_info,
        "click": cmd_click,
        "click-element": cmd_click_element,
        "swipe": cmd_swipe,
        "input-text": cmd_input_text,
        "input-element": cmd_input_element,
        "screenshot": cmd_screenshot,
        "dump": cmd_dump,
        "wait-element": cmd_wait_element,
        "exists": cmd_exists,
        "start-app": cmd_start_app,
        "stop-app": cmd_stop_app,
        "list-apps": cmd_list_apps,
        "install": cmd_install,
    }
    try:
        handlers[args.cmd](args)
        return 0
    except Exception as e:
        _out(False, error=str(e), cmd=args.cmd)
        return 1


if __name__ == "__main__":
    sys.exit(main())
