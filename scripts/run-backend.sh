#!/usr/bin/env bash
# 启动后端。公司库走内网 192.168.10.5:3306。
# JVM 不走系统 / SOCKS 代理（空 -DsocksProxyHost= 反而会进 SocksSocketImpl）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HOST="192.168.10.5"
PORT="3306"
if [[ -f "$ROOT/.db-profile.properties" ]] \
  && grep -q 'spring.profiles.active=home' "$ROOT/.db-profile.properties"; then
  HOST="127.0.0.1"
  PORT="3306"
fi

if ! nc -z -w 3 "$HOST" "$PORT" >/dev/null 2>&1; then
  echo "数据库 ${HOST}:${PORT} 不通。检查公司网 / 本机 MySQL。"
  exit 1
fi
echo "数据库 ${HOST}:${PORT} 已通"

unset ALL_PROXY all_proxy SOCKS_PROXY HTTPS_PROXY HTTP_PROXY https_proxy http_proxy || true
export JAVA_TOOL_OPTIONS="-Djava.net.useSystemProxies=false -Dhttp.nonProxyHosts=localhost|127.*|192.168.*|[::1]|118.25.144.133 -DsocksNonProxyHosts=localhost|127.*|192.168.*|[::1]|118.25.144.133 -Dhttps.proxyHost= -Dhttp.proxyHost="

exec mvn spring-boot:run
