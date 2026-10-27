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
SECRETS="$ROOT/scripts/db-secrets.local"
if [[ "$choice" == "home" && -f "$SECRETS" ]]; then
  # shellcheck disable=SC1090
  source "$SECRETS"
  {
    printf 'HOME_DB_HOST=%s\n' "${HOME_DB_HOST:-127.0.0.1}"
    printf 'HOME_DB_PORT=%s\n' "${HOME_DB_PORT:-3306}"
    printf 'HOME_DB_USERNAME=%s\n' "${HOME_DB_USER:-root}"
    printf 'HOME_DB_PASSWORD=%s\n' "${HOME_DB_PASSWORD:-}"
  } >> "$OUT"
fi
echo "已切换到 ${choice}。重启后端后生效（mvn spring-boot:run）。"
if [[ "$choice" == "home" ]]; then
  echo "本机库：${HOME_DB_HOST:-127.0.0.1}:${HOME_DB_PORT:-3306} / mobile_standalone"
  echo "第一次用：./scripts/local-db.sh setup 建本机独立实例（3316）"
else
  echo "公司库：118.25.144.133:13306（公网直连）"
fi
