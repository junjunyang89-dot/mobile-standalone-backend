#!/usr/bin/env python3
"""iOS Tidevice NDJSON performance sampler for mobile-standalone-backend.

Stdout: one JSON object per line (same contract as Android SoloX):
  {"ts":1710000000,"cpu":12.5,"mem_pss_kb":180000,"fps":58.0}

--ping: verify tidevice import (exit 0 on success).
Note: iOS 17+ may be unstable due to CoreDevice; failures should exit non-zero or emit errors.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from typing import Any, Optional


def emit(obj: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def ping() -> int:
    try:
        import tidevice  # noqa: F401
        ver = getattr(tidevice, "__version__", "unknown")
        emit({"ok": True, "engine": "tidevice", "version": ver})
        return 0
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}), file=sys.stderr)
        return 1


def _to_mem_kb(val: Any) -> Optional[int]:
    if val is None:
        return None
    try:
        f = float(val)
    except (TypeError, ValueError):
        return None
    # tidevice memory often in MB; treat large numbers as already KB
    if f < 50000:
        return int(f * 1024)
    return int(f)


def normalize_stat(raw: Any) -> dict[str, Any]:
    """Map tidevice payloads into platform NDJSON fields."""
    row: dict[str, Any] = {"ts": int(time.time())}
    if raw is None:
        return row
    if not isinstance(raw, dict):
        row["raw_type"] = type(raw).__name__
        return row

    # Flat dict: {'cpu': 12.5, 'memory': 150.2, 'fps': 59, ...}
    if "cpu" in raw or "memory" in raw or "fps" in raw:
        if raw.get("cpu") is not None:
            try:
                row["cpu"] = float(raw["cpu"])
            except (TypeError, ValueError):
                pass
        mem = raw.get("memory") or raw.get("mem") or raw.get("mem_pss_kb")
        kb = _to_mem_kb(mem)
        if kb is not None:
            row["mem_pss_kb"] = kb
        if raw.get("fps") is not None:
            try:
                row["fps"] = float(raw["fps"])
            except (TypeError, ValueError):
                pass
        # network may be nested
        net = raw.get("network") or {}
        if isinstance(net, dict):
            rx = net.get("rx") or net.get("downFlow") or net.get("received")
            tx = net.get("tx") or net.get("upFlow") or net.get("sent")
            if rx is not None:
                try:
                    row["network_rx"] = int(float(rx))
                except (TypeError, ValueError):
                    pass
            if tx is not None:
                try:
                    row["network_tx"] = int(float(tx))
                except (TypeError, ValueError):
                    pass
        if "timestamp" in raw:
            try:
                row["ts"] = int(raw["timestamp"])
            except (TypeError, ValueError):
                pass
        return row

    # Typed callback style: {'type': 'cpu', 'value': {...}}
    t = str(raw.get("type") or raw.get("data_type") or "").lower()
    val = raw.get("value") if "value" in raw else raw
    if isinstance(val, dict):
        if "cpu" in t or "cpu" in val:
            v = val.get("cpu") or val.get("value") or val.get("AppCPU") or val.get("total")
            if v is not None:
                try:
                    row["cpu"] = float(v)
                except (TypeError, ValueError):
                    pass
        if "mem" in t or "memory" in t or "memory" in val:
            v = val.get("memory") or val.get("value") or val.get("phys_footprint") or val.get("pid_memory")
            kb = _to_mem_kb(v)
            if kb is not None:
                row["mem_pss_kb"] = kb
        if "fps" in t or "fps" in val:
            v = val.get("fps") or val.get("value")
            if v is not None:
                try:
                    row["fps"] = float(v)
                except (TypeError, ValueError):
                    pass
    return row


def run_with_performance_api(udid: str, bundle: str, interval: float) -> int:
    """Preferred: tidevice._perf.Performance with callback → aggregate latest → emit loop."""
    from tidevice import Device

    try:
        from tidevice._perf import DataType, Performance
    except Exception:
        from tidevice.perf import DataType, Performance  # type: ignore

    d = Device(udid)
    latest: dict[str, Any] = {}
    lock = threading.Lock()

    def on_data(dtype, value):
        with lock:
            name = str(getattr(dtype, "value", None) or getattr(dtype, "name", dtype)).lower()
            if isinstance(value, dict):
                if name == "cpu" or "cpu" in name:
                    v = value.get("value", value.get("AppCPU", value.get("cpu")))
                    if v is not None:
                        latest["cpu"] = float(v)
                elif name == "memory" or "mem" in name:
                    v = value.get("value") or value.get("pid_memory") or value.get("phys_footprint") or value.get("memory")
                    kb = _to_mem_kb(v)
                    if kb is not None:
                        latest["mem_pss_kb"] = kb
                elif name == "fps" or "fps" in name:
                    v = value.get("fps", value.get("value"))
                    if v is not None:
                        latest["fps"] = float(v)
                elif name == "network" or "network" in name:
                    rx = value.get("rx") or value.get("downFlow")
                    tx = value.get("tx") or value.get("upFlow")
                    if rx is not None:
                        latest["network_rx"] = int(float(rx))
                    if tx is not None:
                        latest["network_tx"] = int(float(tx))
            elif isinstance(value, (int, float)):
                if "cpu" in name:
                    latest["cpu"] = float(value)
                elif "fps" in name:
                    latest["fps"] = float(value)
                elif "mem" in name:
                    kb = _to_mem_kb(value)
                    if kb is not None:
                        latest["mem_pss_kb"] = kb

    subtypes = []
    for attr in ("CPU", "MEMORY", "FPS", "NETWORK"):
        if hasattr(DataType, attr):
            subtypes.append(getattr(DataType, attr))
    if not subtypes:
        raise RuntimeError("tidevice DataType has no CPU/MEMORY/FPS")

    # 本机 tidevice 签名是 Performance(d, perfs=[...])；旧文档/别名曾用 subtypes=
    try:
        perf = Performance(d, perfs=subtypes)
    except TypeError:
        try:
            perf = Performance(d, subtypes=subtypes)
        except TypeError:
            perf = Performance(d, subtypes)
    perf.start(bundle, callback=on_data)
    try:
        while True:
            with lock:
                snap = dict(latest)
            if snap:
                snap["ts"] = int(time.time())
                emit(snap)
            else:
                emit({"ts": int(time.time()), "waiting": True})
            time.sleep(max(0.5, interval))
    finally:
        try:
            perf.stop()
        except Exception:
            pass


def run_with_iter_api(udid: str, bundle: str, interval: float) -> int:
    """Fallback: for stat in d.perf.start(bundle)（部分旧版才有）。"""
    from tidevice import Device

    d = Device(udid)
    perf_attr = getattr(d, "perf", None)
    if perf_attr is None:
        raise RuntimeError("Device has no .perf（当前 tidevice 请用 Performance(perfs=...)）")

    start = getattr(perf_attr, "start", None)
    if start is None:
        raise RuntimeError("Device.perf has no start()")

    gen = start(bundle)
    last_emit = 0.0
    for stat in gen:
        row = normalize_stat(stat)
        now = time.time()
        if now - last_emit >= max(0.5, interval):
            emit(row)
            last_emit = now
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="iOS Tidevice NDJSON performance sampler")
    ap.add_argument("--ping", action="store_true")
    ap.add_argument("--device", default="", help="iOS UDID")
    ap.add_argument("--pkg", default="", help="Bundle ID")
    ap.add_argument("--interval", type=float, default=2.0)
    args = ap.parse_args()

    if args.ping:
        return ping()

    if not args.device or not args.pkg:
        print("device and pkg required", file=sys.stderr)
        return 2

    interval = max(0.5, float(args.interval))
    errors: list[str] = []
    try:
        return run_with_performance_api(args.device, args.pkg, interval) or 0
    except Exception as e:
        errors.append(f"Performance API: {e}")
    try:
        return run_with_iter_api(args.device, args.pkg, interval) or 0
    except Exception as e:
        errors.append(f"iter API: {e}")

    emit({"ts": int(time.time()), "error": "; ".join(errors)})
    print("; ".join(errors), file=sys.stderr)
    return 3


if __name__ == "__main__":
    sys.exit(main())
