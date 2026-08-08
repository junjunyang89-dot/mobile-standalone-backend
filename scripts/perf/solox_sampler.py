#!/usr/bin/env python3
"""SoloX / ADB NDJSON performance sampler for mobile-standalone-backend.

Stdout: one JSON object per line:
  {"ts":1710000000,"cpu":12.5,"mem_pss_kb":180000,"fps":58.0,"jank":1.2}

--ping: verify solox import (exit 0 on success).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from typing import Any, Optional


def emit(obj: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def adb_shell(device: str, *args: str, timeout: float = 8.0) -> str:
    cmd = ["adb", "-s", device, "shell", *args]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")
    except Exception:
        return ""


def ping() -> int:
    try:
        import solox  # noqa: F401
        emit({"ok": True, "engine": "solox", "version": getattr(solox, "__version__", "unknown")})
        return 0
    except Exception as e:
        print(json.dumps({"ok": False, "error": str(e)}), file=sys.stderr)
        return 1


def cpu_from_adb(device: str, pkg: str) -> Optional[float]:
    out = adb_shell(device, "dumpsys", "cpuinfo")
    pat = re.compile(rf"\s*(\d+(?:\.\d+)?)%\s+\d+/{re.escape(pkg)}")
    for line in out.splitlines():
        if pkg not in line:
            continue
        m = pat.search(line)
        if m:
            return float(m.group(1))
    # top fallback
    out = adb_shell(device, "top", "-n", "1", "-b", "-d", "0.3")
    short = pkg[-15:] if len(pkg) > 15 else pkg
    for line in out.splitlines():
        if pkg not in line and short not in line:
            continue
        parts = line.split()
        for i, part in enumerate(parts):
            if pkg in part or short in part:
                for j in range(max(0, i - 6), i):
                    try:
                        v = float(parts[j].replace("%", ""))
                        if 0 <= v <= 800:
                            return v
                    except ValueError:
                        pass
    return None


def mem_pss_kb(device: str, pkg: str) -> Optional[int]:
    out = adb_shell(device, "dumpsys", "meminfo", pkg)
    for line in out.splitlines():
        t = line.strip()
        if t.startswith("TOTAL"):
            parts = t.split()
            if len(parts) >= 4:
                try:
                    return int(parts[3])
                except ValueError:
                    pass
        m = re.search(r"TOTAL\s+\d+\s+\d+\s+(\d+)", line)
        if m:
            return int(m.group(1))
    return None


def fps_jank_from_gfx(device: str, pkg: str) -> tuple[Optional[float], Optional[float]]:
    out = adb_shell(device, "dumpsys", "gfxinfo", pkg)
    try:
        adb_shell(device, "dumpsys", "gfxinfo", pkg, "reset", timeout=3.0)
    except Exception:
        pass
    fps = None
    jank = None
    m = re.search(r"Janky frames:\s*\d+\s*\(([\d.]+)\s*%\)", out)
    if m:
        jank = float(m.group(1))
    m50 = re.search(r"50th percentile:\s*(\d+)\s*ms", out)
    if m50:
        p50 = int(m50.group(1))
        if p50 > 0:
            fps = min(120.0, 1000.0 / p50)
    return fps, jank


def try_solox_sample(device: str, pkg: str) -> Optional[dict[str, Any]]:
    """Best-effort SoloX API; return partial metrics dict or None."""
    try:
        from solox.public.apm import AppPerformanceMonitor  # type: ignore

        mon = AppPerformanceMonitor(
            pkgName=pkg,
            platform="Android",
            deviceId=device,
            surfaceview=True,
            noLog=True,
        )
        data: dict[str, Any] = {}
        for name, key in (
            ("collectCpu", "cpu"),
            ("collectMemory", "mem_pss_kb"),
            ("collectFps", "fps"),
        ):
            fn = getattr(mon, name, None)
            if not callable(fn):
                continue
            try:
                val = fn()
                if isinstance(val, dict):
                    # common shapes
                    if "cpu" in val:
                        data["cpu"] = float(val["cpu"])
                    if "memory" in val:
                        mem = val["memory"]
                        data["mem_pss_kb"] = int(float(mem) * 1024) if float(mem) < 100000 else int(mem)
                    if "fps" in val:
                        data["fps"] = float(val["fps"])
                elif isinstance(val, (int, float)):
                    data[key] = float(val) if key != "mem_pss_kb" else int(val)
            except Exception:
                continue
        return data or None
    except Exception:
        return None


def sample_once(device: str, pkg: str) -> dict[str, Any]:
    row: dict[str, Any] = {"ts": int(time.time())}
    sx = try_solox_sample(device, pkg)
    if sx:
        row.update(sx)
    if "cpu" not in row:
        cpu = cpu_from_adb(device, pkg)
        if cpu is not None:
            row["cpu"] = cpu
    if "mem_pss_kb" not in row:
        mem = mem_pss_kb(device, pkg)
        if mem is not None:
            row["mem_pss_kb"] = mem
    if "fps" not in row or "jank" not in row:
        fps, jank = fps_jank_from_gfx(device, pkg)
        if "fps" not in row and fps is not None:
            row["fps"] = fps
        if jank is not None:
            row["jank"] = jank
            row["jank_percent"] = jank
    return row


def main() -> int:
    ap = argparse.ArgumentParser(description="SoloX NDJSON performance sampler")
    ap.add_argument("--ping", action="store_true", help="Probe solox import and exit")
    ap.add_argument("--device", default="")
    ap.add_argument("--pkg", default="")
    ap.add_argument("--interval", type=float, default=2.0, help="Seconds between samples")
    args = ap.parse_args()

    if args.ping:
        return ping()

    if not args.device or not args.pkg:
        print("device and pkg required", file=sys.stderr)
        return 2

    interval = max(0.5, float(args.interval))
    while True:
        try:
            emit(sample_once(args.device, args.pkg))
        except Exception as e:
            emit({"ts": int(time.time()), "error": str(e)})
        time.sleep(interval)


if __name__ == "__main__":
    sys.exit(main())
