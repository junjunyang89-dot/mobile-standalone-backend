#!/usr/bin/env bash
# 在家连本机库，公司共享库走公网 118.25.144.133:13306。
#   ./scripts/use-db.sh home
#   ./scripts/use-db.sh office
#   ./scripts/use-db.sh          # 看当前
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.db-profile.properties"

choice="${1:-}"
if [[ -z "$choice" ]]; then
  if [[ -f "$OUT" ]]; then
    echo "当前：$(grep -E '^spring.profiles.active=' "$OUT" | cut -d= -f2)"
  else
    echo "还没选过库，默认走 application.yml（公网 118.25.144.133:13306）。"
    echo "在家请执行：  ./scripts/use-db.sh home"
    echo "在公司请执行：./scripts/use-db.sh office"
  fi
  exit 0
fi

if [[ "$choice" != "home" && "$choice" != "office" ]]; then
  echo "用法：./scripts/use-db.sh home|office" >&2
  exit 1
fi

printf 'spring.profiles.active=%s\n' "$choice" > "$OUT"
echo "已切换到 ${choice}。重启后端后生效（mvn spring-boot:run）。"
if [[ "$choice" == "home" ]]; then
  echo "本机库：127.0.0.1:3306 / mobile_standalone"
  echo "第一次在家用：先起本机 MySQL，再 ./scripts/apply-sql.sh init-home"
else
  echo "公司库：118.25.144.133:13306（公网直连）"
fi
