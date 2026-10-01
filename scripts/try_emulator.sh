#!/usr/bin/env bash
# 模拟器可行性验证：APP 到底能不能在模拟器上装上、起来、活过前几秒。
#
# 为什么要先跑这个：平台侧根本不用改就能认出模拟器（adb devices 扫到就算），
# 所以「能不能加模拟器」这个问题真正的变数不在平台，在你们的 APP——
# 生产 APP 常见三种拦法：
#   ① 只带 arm64 的 .so，x86 镜像装得上跑不起来（Apple 芯片装 arm64 镜像就没这问题）
#   ② 缺 Google Play 服务
#   ③ 自己有模拟器/root 检测，起来就退
# 三种现象都是「装上了、点开闪退」，光看现象分不出是哪种，得看 ABI 和日志。
#
# 用法：
#   scripts/try_emulator.sh --list
#   scripts/try_emulator.sh --avd Pixel_7_API_34 --apk ~/Downloads/app-debug.apk --pkg com.pwrd.steam.esports
#   scripts/try_emulator.sh --avd Pixel_7_API_34 --headless          # 只起模拟器，不装包
#
# 建议先拿 **DEBUG 包 + 测试环境** 试，别拿生产包：生产包的风控最严，
# 而你要模拟器本来就是为了调试。

set -uo pipefail

AVD=""
APK=""
PKG=""
HEADLESS=0
DO_LIST=0
BOOT_TIMEOUT=180

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd) AVD="${2:-}"; shift 2 ;;
    --apk) APK="${2:-}"; shift 2 ;;
    --pkg) PKG="${2:-}"; shift 2 ;;
    --headless) HEADLESS=1; shift ;;
    --list) DO_LIST=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✅ %s\033[0m\n' "$*"; }
bad()  { printf '  \033[31m❌ %s\033[0m\n' "$*"; }
warn() { printf '  \033[33m⚠️  %s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }

# ---------- 0. 工具齐不齐 ----------
say "0. 检查工具"
MISSING=0
for t in adb emulator; do
  if command -v "$t" >/dev/null 2>&1; then
    ok "$t: $(command -v "$t")"
  else
    bad "$t 没找到"
    MISSING=1
  fi
done
if [[ $MISSING -eq 1 ]]; then
  cat <<'EOF'

  adb / emulator 来自 Android SDK。装了 Android Studio 的话，把这两行加进 ~/.zshrc：
      export ANDROID_HOME="$HOME/Library/Android/sdk"
      export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
  没装 Android Studio 就先装它，SDK 跟着一起来。
EOF
  exit 1
fi

# ---------- 1. 有哪些 AVD ----------
say "1. 本机的模拟器（AVD）"
AVDS="$(emulator -list-avds 2>/dev/null | sed '/^$/d')"
if [[ -z "$AVDS" ]]; then
  bad "一个 AVD 都没有"
  cat <<'EOF'

  最省事的建法：Android Studio → Device Manager → Create Device。
  Apple 芯片的 Mac 上，系统镜像挑 **arm64-v8a**（不是 x86_64）：
  arm64 镜像跑 arm64 的 APK 是原生执行，不用转译，性能和兼容性都好得多。
  要能登录/用到推送的话，镜像选带 "Google Play" 的那一档。
EOF
  exit 1
fi
echo "$AVDS" | sed 's/^/  /'
[[ $DO_LIST -eq 1 ]] && exit 0

if [[ -z "$AVD" ]]; then
  AVD="$(echo "$AVDS" | head -1)"
  warn "没指定 --avd，用第一个：$AVD"
fi

# ---------- 2. 起模拟器 ----------
say "2. 启动 $AVD"
RUNNING="$(adb devices | awk '/^emulator-/{print $1}' | head -1)"
if [[ -n "$RUNNING" ]]; then
  warn "已经有模拟器在跑：$RUNNING，直接用它（不重复启动）"
  SERIAL="$RUNNING"
else
  EXTRA=()
  [[ $HEADLESS -eq 1 ]] && EXTRA+=(-no-window)
  # -no-snapshot-load：每次干净启动。快照省时间，但「上次残留的登录态/弹窗」
  # 会让同一条用例时过时不过，这种偶现比多等 30 秒贵得多
  nohup emulator -avd "$AVD" -no-snapshot-load "${EXTRA[@]}" >/tmp/emulator_"$AVD".log 2>&1 &
  info "已后台拉起，日志 /tmp/emulator_$AVD.log"
  info "等它连上 adb…"
  adb wait-for-device
  SERIAL="$(adb devices | awk '/^emulator-/{print $1}' | head -1)"
fi
[[ -z "$SERIAL" ]] && { bad "没等到模拟器上线，看 /tmp/emulator_$AVD.log"; exit 1; }
ok "序列号：$SERIAL"

info "等系统起完（最多 ${BOOT_TIMEOUT}s）…"
for ((i=0; i<BOOT_TIMEOUT; i++)); do
  [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 1
done
if [[ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  bad "${BOOT_TIMEOUT}s 还没起完，多半是镜像和机器架构不匹配（Apple 芯片装了 x86 镜像）"
  exit 1
fi
adb -s "$SERIAL" shell input keyevent 82 >/dev/null 2>&1   # 解锁滑屏
ok "系统已就绪"

ABI="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
ABILIST="$(adb -s "$SERIAL" shell getprop ro.product.cpu.abilist | tr -d '\r')"
REL="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
info "Android $REL · 主 ABI $ABI · 支持 $ABILIST"

# ---------- 3. 平台能不能扫到 ----------
say "3. 平台能不能认出它"
adb devices -l | grep "^$SERIAL" | sed 's/^/  /'
ok "这一行就是平台扫描读的东西，设备列表里会直接出现，不用改代码"
warn "但它的「环境」是空的，而平台里空=按生产跑（TestEnvironment.normalize(null)→PROD）。"
info "   进设备页把这台的环境改成「测试」再跑流程，否则会去取生产账号变量。"

# ---------- 4. APK 的 ABI 对不对得上 ----------
if [[ -n "$APK" ]]; then
  say "4. 这个包的 native 库支持哪些架构"
  if [[ ! -f "$APK" ]]; then
    bad "找不到 APK: $APK"; exit 1
  fi
  APK_ABIS="$(unzip -l "$APK" 2>/dev/null | awk '/lib\//{print $4}' | awk -F/ '{print $2}' | sort -u | sed '/^$/d')"
  if [[ -z "$APK_ABIS" ]]; then
    ok "包里没有 native 库（纯 Java/Kotlin），架构上不会有问题"
  else
    info "包里带的架构：$(echo "$APK_ABIS" | tr '\n' ' ')"
    if echo "$APK_ABIS" | grep -qx "$ABI"; then
      ok "和模拟器的 $ABI 对得上"
    else
      bad "包里没有 $ABI —— 装得上但一跑就崩，这是架构不匹配，不是代码问题"
      info "   解法：换一个 $(echo "$APK_ABIS" | head -1) 架构的系统镜像，或让开发出一个带 $ABI 的包"
    fi
  fi

  # ---------- 5. 装 + 起 + 看它活不活 ----------
  say "5. 安装并启动"
  if [[ -z "$PKG" ]]; then
    if command -v aapt >/dev/null 2>&1; then
      PKG="$(aapt dump badging "$APK" 2>/dev/null | awk -F"'" '/^package: name=/{print $2}')"
    fi
    [[ -n "$PKG" ]] && info "包名（从 APK 读出）：$PKG" || warn "没读到包名，加 --pkg 才能判断是否闪退"
  fi

  info "adb install -r -t …（大包要等一会）"
  if adb -s "$SERIAL" install -r -t "$APK" 2>&1 | tail -3 | sed 's/^/    /'; then
    ok "安装完成"
  else
    bad "安装失败，看上面的报错"
  fi

  if [[ -n "$PKG" ]]; then
    adb -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    info "已拉起，等 10 秒看它活不活…"
    sleep 10
    PID="$(adb -s "$SERIAL" shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
    SHOT="/tmp/emulator_${PKG}_$(date +%H%M%S).png"
    adb -s "$SERIAL" exec-out screencap -p > "$SHOT" 2>/dev/null
    if [[ -n "$PID" ]]; then
      ok "10 秒后进程还在（pid $PID）—— 这一关过了"
      info "截图：$SHOT（打开看看是正常界面，还是卡在启动页/风控提示）"
      info "接下来真正要验的是**能不能登录**，那个脚本代替不了，得你手点一次"
    else
      bad "起来又没了，是闪退"
      info "崩溃日志（最后 30 行）："
      adb -s "$SERIAL" logcat -d -t 300 2>/dev/null \
        | grep -iE "FATAL|AndroidRuntime|$PKG" | tail -30 | sed 's/^/    /'
      cat <<'EOF'

    对着日志分三种情况：
      · UnsatisfiedLinkError / dlopen failed  → 架构不匹配，见上一步
      · Google Play services / GooglePlayServicesUtil → 换带 Google Play 的镜像
      · 自家类名里带 emulator / root / security / risk → APP 自己的模拟器检测，
        这种绕不过去，只能找开发要一个关掉检测的调试包
EOF
    fi
  fi
fi

say "结论"
cat <<EOF
  模拟器序列号：$SERIAL（平台设备列表里应该已经能看到）
  记得先把这台设备的「环境」设成测试，再去跑流程。

  下一步建议：
    · 先在模拟器上跑一遍「元素点选采集」和一条最简单的流程试跑，
      这两件事最吃真机、也最不挑设备，收益最直接
    · 生产环境 UI 巡检、性能测试不要用模拟器：
      巡检要的是真实线上表现，性能数据在模拟器上没有参考价值
EOF
