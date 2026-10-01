#!/usr/bin/env bash
# 检查/提示安装 hdc + hmdriver2（纯鸿蒙自动化前置）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
echo "== 鸿蒙环境检查 =="

HDC_FOUND=""
if command -v hdc >/dev/null 2>&1; then
  HDC_FOUND="$(command -v hdc)"
elif [[ -n "${HDC_PATH:-}" && -x "${HDC_PATH}" ]]; then
  HDC_FOUND="${HDC_PATH}"
else
  for root in \
    "$HOME/Library/Huawei/Sdk" \
    "$HOME/Library/OpenHarmony/Sdk" \
    "/Applications/DevEco-Studio.app/Contents/sdk"; do
    if [[ -d "$root" ]]; then
      cand="$(find "$root" -name hdc -type f 2>/dev/null | head -1 || true)"
      if [[ -n "$cand" && -x "$cand" ]]; then
        HDC_FOUND="$cand"
        break
      fi
    fi
  done
fi

if [[ -z "$HDC_FOUND" ]]; then
  echo "[缺失] 未找到 hdc"
  echo "请安装 DevEco Studio 并勾选 HarmonyOS SDK → Toolchains："
  echo "  https://developer.huawei.com/consumer/cn/deveco-studio/"
  echo "装完后 hdc 通常在："
  echo "  ~/Library/Huawei/Sdk/harmonyos/<version>/toolchains/hdc"
  echo "然后执行："
  echo "  export PATH=\"\$PATH:/path/to/toolchains\""
  echo "  # 或在 application.yml 设置 harmony.hdc-path"
  echo "  hdc version"
  echo "  hdc list targets"
else
  echo "[OK] hdc = $HDC_FOUND"
  "$HDC_FOUND" version || true
  echo "--- list targets ---"
  "$HDC_FOUND" list targets || true
fi

echo
echo "== DevEco 鸿蒙模拟器 =="
EMU=""
for cand in \
  "/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator" \
  "$HOME/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator"; do
  if [[ -x "$cand" ]]; then
    EMU="$cand"
    break
  fi
done
if [[ -z "$EMU" ]]; then
  echo "[缺失] 未找到 DevEco Emulator"
  echo "  通常在 /Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator"
else
  echo "[OK] Emulator = $EMU"
  "$EMU" -version || true
  echo "--- list ---"
  "$EMU" -list || true
fi

echo
echo "== Python hmdriver2 =="
python3 -m pip install -r "$ROOT/scripts/harmony/requirements.txt" -q
python3 "$ROOT/scripts/harmony/hmdriver_bridge.py" ping
echo
echo "手机需：USB 连接 + 开启开发者模式/USB 调试(hdc) + 信任本机。"
echo "详见 docs/鸿蒙hdc自动化.md"
