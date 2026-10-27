#!/usr/bin/env bash
# 每天自动备份本机库（127.0.0.1:3316）。
# 主备份永远写在内盘；外置盘插着时再复制一份。
# 安装：./scripts/backup-local-db-daily.sh install
# 卸载：./scripts/backup-local-db-daily.sh uninstall
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_DB="$ROOT/scripts/local-db.sh"
HOME_BACKUP="$HOME/.mobile-standalone/mysql/backup"
EXT_BACKUP="/Volumes/MOVESPEED/mobile-standalone/db-backups"
LABEL="com.mobile-standalone.mysql-backup"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
LOG="$HOME/.mobile-standalone/mysql/backup/daily-backup.log"
KEEP=14

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"; }

run_backup() {
  mkdir -p "$HOME_BACKUP"
  if ! "$LOCAL_DB" status 2>/dev/null | grep -q "运行中"; then
    # 尝试拉起本机库再备（不依赖外置盘）
    "$LOCAL_DB" start >>"$LOG" 2>&1 || {
      log "跳过：本机 MySQL 未运行且无法启动"
      return 1
    }
  fi
  log "开始备份…"
  out="$("$LOCAL_DB" backup 2>&1 | tee -a "$LOG")"
  sql="$(echo "$out" | sed -n 's/^已备份：//p' | tail -1)"
  if [[ -z "$sql" || ! -f "$sql" ]]; then
    log "备份失败：没有得到 sql 文件"
    return 1
  fi
  if [[ -d /Volumes/MOVESPEED ]]; then
    mkdir -p "$EXT_BACKUP"
    cp "$sql" "$EXT_BACKUP/" && log "已复制到外置盘：$EXT_BACKUP/$(basename "$sql")"
  else
    log "外置盘未挂载，仅保留内盘备份"
  fi
  # 只保留内盘最近 KEEP 份自动命名的库备份（不删 recovered/手动命名）
  ls -1t "$HOME_BACKUP"/mobile_standalone-20*.sql 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r f; do
    rm -f "$f" && log "清理旧备份：$(basename "$f")"
  done || true
  log "完成：$sql"
}

install() {
  mkdir -p "$(dirname "$PLIST")" "$HOME_BACKUP"
  cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${ROOT}/scripts/backup-local-db-daily.sh</string>
    <string>run</string>
  </array>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Hour</key><integer>12</integer>
    <key>Minute</key><integer>30</integer>
  </dict>
  <key>StandardOutPath</key><string>${LOG}</string>
  <key>StandardErrorPath</key><string>${LOG}</string>
</dict>
</plist>
EOF
  launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "${PLIST}"
  log "已安装每日备份（每天 12:30）。立刻试跑：./scripts/backup-local-db-daily.sh run"
  echo "已安装：每天 12:30 自动备份到 ${HOME_BACKUP}（外置盘在则再复制一份）"
}

uninstall() {
  launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
  rm -f "$PLIST"
  echo "已卸载每日备份"
}

case "${1:-run}" in
  run) run_backup ;;
  install) install ;;
  uninstall) uninstall ;;
  *)
    echo "用法：./scripts/backup-local-db-daily.sh run|install|uninstall" >&2
    exit 1
    ;;
esac
