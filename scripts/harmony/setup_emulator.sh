#!/usr/bin/env bash
# 主控机：签协议、确认 Phone 镜像、建/列出鸿蒙模拟器实例。
# 镜像大约 2.4GB，没下完时这个脚本不会硬等，只告诉你进度。
set -euo pipefail

EMU="${DEVECO_EMULATOR:-/Applications/DevEco-Studio.app/Contents/tools/emulator/Emulator}"
NAME="${HARMONY_EMU_NAME:-Phone}"
OS_VERSION="${HARMONY_EMU_OS:-HarmonyOS 6.1.1(24)}"

if [[ ! -x "$EMU" ]]; then
  echo "[缺失] DevEco Emulator：$EMU"
  echo "装 DevEco Studio 后重试。详见 docs/设备池-主控机搭建.md"
  exit 1
fi

echo "== Emulator = $EMU"
"$EMU" -version || true
echo
echo "== 签协议 =="
"$EMU" -license accept >/dev/null
echo "已接受"
echo
echo "== 已下载镜像 =="
"$EMU" -imageList -downloaded true || true
echo
echo "== 实例 =="
"$EMU" -list || true

if "$EMU" -list 2>/dev/null | grep -q "$NAME"; then
  echo
  echo "[OK] 已有实例 $NAME。设备池里点启动，或："
  echo "  \"$EMU\" -start $NAME"
  exit 0
fi

if "$EMU" -imageList -downloaded true 2>/dev/null | grep -qi "phone"; then
  echo
  echo "== 创建 $NAME =="
  "$EMU" -create "$NAME" -deviceType Phone -osVersion "$OS_VERSION"
  echo
  "$EMU" -list
  echo
  echo "创建完成。设备池刷新后会出现这一行；或："
  echo "  \"$EMU\" -start $NAME"
  exit 0
fi

echo
echo "Phone 镜像还没下完（大约 2.4GB）。正在下的话看："
echo "  tail -f /tmp/harmony-emu-install.log"
echo "没在下就执行："
echo "  \"$EMU\" -install -deviceType Phone -osVersion \"$OS_VERSION\" -force"
echo "下完再跑一遍本脚本创建实例。"
exit 2
