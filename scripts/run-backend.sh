#!/usr/bin/env bash
# 先确认本机 3307 隧道，再启动后端。JVM 不走系统 / SOCKS 代理。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! nc -z 127.0.0.1 3307 >/dev/null 2>&1; then
  echo "127.0.0.1:3307 还没通。先开隧道："
  echo "  ssh -N -L 3307:127.0.0.1:3306 yangjun@192.168.10.5"
  echo "或：./scripts/db-tunnel.sh"
  echo "另一个窗口执行 nc -zv 127.0.0.1 3307，看到 succeeded 再启动。"
  exit 1
fi
echo "nc 127.0.0.1 3307 已通"

unset ALL_PROXY all_proxy SOCKS_PROXY HTTPS_PROXY HTTP_PROXY https_proxy http_proxy || true
export JAVA_TOOL_OPTIONS="-Djava.net.useSystemProxies=false -DsocksProxyHost= -Dhttp.nonProxyHosts=localhost|127.*|192.168.*|[::1]"

exec mvn spring-boot:run
