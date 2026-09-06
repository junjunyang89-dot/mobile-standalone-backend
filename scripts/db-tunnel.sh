#!/usr/bin/env bash
# 把 Mini 上的 MySQL 转到本机 3307。Java 只连 127.0.0.1:3307，不走公网、不走代理。
#
#   ./scripts/db-tunnel.sh         # 前台挂着（关窗口即断）
#   ./scripts/db-tunnel.sh start   # 后台
#   ./scripts/db-tunnel.sh status
#   ./scripts/db-tunnel.sh stop
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="$ROOT/scripts/db-secrets.local"
PID_FILE="$ROOT/.db-tunnel.pid"

SSH_USER="${DB_TUNNEL_USER:-yangjun}"
SSH_HOST="${DB_TUNNEL_HOST:-192.168.10.5}"
LOCAL_PORT="${DB_TUNNEL_LOCAL_PORT:-3307}"
REMOTE="${DB_TUNNEL_REMOTE:-127.0.0.1:3306}"

if [[ -f "$SECRETS" ]]; then
  # shellcheck disable=SC1090
  source "$SECRETS"
  SSH_USER="${DB_TUNNEL_USER:-$SSH_USER}"
  SSH_HOST="${DB_TUNNEL_HOST:-$SSH_HOST}"
  LOCAL_PORT="${DB_TUNNEL_LOCAL_PORT:-$LOCAL_PORT}"
  REMOTE="${DB_TUNNEL_REMOTE:-$REMOTE}"
fi

listening() {
  nc -z 127.0.0.1 "$LOCAL_PORT" >/dev/null 2>&1
}

cmd="${1:-fg}"

case "$cmd" in
  status)
    echo "检查：nc -zv 127.0.0.1 ${LOCAL_PORT}"
    if nc -zv 127.0.0.1 "$LOCAL_PORT"; then
      echo "succeeded。可以启动后端。"
      lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR==1 || /ssh/' || true
    else
      echo "未通。先执行：ssh -N -L ${LOCAL_PORT}:${REMOTE} ${SSH_USER}@${SSH_HOST}"
      exit 1
    fi
    ;;
  stop)
    if [[ -f "$PID_FILE" ]]; then
      pid="$(cat "$PID_FILE")"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid" || true
      fi
      rm -f "$PID_FILE"
    fi
    pids="$(lsof -nP -t -iTCP:"$LOCAL_PORT" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
      # shellcheck disable=SC2086
      kill $pids || true
    fi
    echo "已关闭本机 ${LOCAL_PORT} 隧道"
    ;;
  start)
    if listening; then
      echo "隧道已在跑：127.0.0.1:${LOCAL_PORT}"
      exit 0
    fi
    echo "后台建立隧道 127.0.0.1:${LOCAL_PORT} -> ${SSH_USER}@${SSH_HOST} ${REMOTE}"
    ssh -N -f -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
      -L "${LOCAL_PORT}:${REMOTE}" "${SSH_USER}@${SSH_HOST}"
    sleep 0.3
    lsof -nP -t -iTCP:"$LOCAL_PORT" -sTCP:LISTEN > "$PID_FILE" 2>/dev/null || true
    echo "已开。保持本机进程即可，然后重启后端。"
    ;;
  fg)
    if listening; then
      echo "隧道已在跑：127.0.0.1:${LOCAL_PORT}"
      exit 0
    fi
    echo "前台隧道 127.0.0.1:${LOCAL_PORT} -> ${SSH_USER}@${SSH_HOST} ${REMOTE}"
    echo "不要关这个窗口。另开终端再启后端。"
    exec ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
      -L "${LOCAL_PORT}:${REMOTE}" "${SSH_USER}@${SSH_HOST}"
    ;;
  *)
    echo "用法：./scripts/db-tunnel.sh [start|status|stop]" >&2
    exit 1
    ;;
esac
