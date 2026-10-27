#!/usr/bin/env bash
# 本机独立 MySQL（只给本平台用）：127.0.0.1:3316。
# 数据固定在内盘 ~/.mobile-standalone/mysql（不依赖外置盘 / 稀疏镜像）。
# 大文件仍可放外置盘：/Volumes/MOVESPEED/mobile-standalone/files（有盘才用）。
# 和 Homebrew 默认的 3306 实例互不影响。
#
#   ./scripts/local-db.sh setup              # 建实例 + 开机自启 + 写 secrets
#   ./scripts/local-db.sh status
#   ./scripts/local-db.sh start | stop
#   ./scripts/local-db.sh backup             # 导出到 ~/.mobile-standalone/mysql/backup/
#   ./scripts/local-db.sh import <dump.sql>  # 先自动备份，再整库替换 mobile_standalone
#   ./scripts/local-db.sh pull-office        # 公司库能连时，把公司库整库拉到本机（仍写入本机库）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="$ROOT/scripts/db-secrets.local"
YML="$ROOT/src/main/resources/application.yml"

EXT_ROOT="/Volumes/MOVESPEED/mobile-standalone"
FILES_DIR="${EXT_ROOT}/files"

BASE="${HOME}/.mobile-standalone/mysql"
DATA="${BASE}/data"
CNF="${BASE}/my.cnf"
BACKUP_DIR="${BASE}/backup"
SOCK="/tmp/mysql-mobile-standalone.sock"
PORT=3316
DB_NAME=mobile_standalone
LABEL="com.mobile-standalone.mysql"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"
MYSQLD="/opt/homebrew/opt/mysql/bin/mysqld"

OFFICE_DB_HOST=118.25.144.133
OFFICE_DB_PORT=13306
OFFICE_DB_USER=root
OFFICE_DB_PASSWORD=
HOME_DB_PASSWORD=

if [[ -f "$SECRETS" ]]; then
  # shellcheck disable=SC1090
  source "$SECRETS"
fi

local_mysql() {
  MYSQL_PWD="${HOME_DB_PASSWORD}" mysql -S "${SOCK}" -uroot "$@"
}

running() {
  [[ -S "${SOCK}" ]] && local_mysql -N -e "SELECT 1" >/dev/null 2>&1
}

ensure_base() {
  mkdir -p "${DATA}" "${BACKUP_DIR}"
  if [[ -d /Volumes/MOVESPEED ]]; then
    mkdir -p "${FILES_DIR}"
  fi
}

# 兼容旧命令名：数据已改到内盘，不再需要挂载稀疏镜像
mount_image() { ensure_base; }
unmount_image() { echo "本机库已改用 ${BASE}，无需卸镜像"; }
mounted() { [[ -d "${DATA}" ]]; }

wait_ready() {
  local i
  for i in $(seq 1 60); do
    [[ -S "${SOCK}" ]] && return 0
    sleep 1
  done
  echo "实例 60 秒内没起来，看日志：${BASE}/mysqld.err" >&2
  exit 1
}

write_cnf() {
  mkdir -p "${DATA}" "${BACKUP_DIR}"
  cat >"${CNF}" <<EOF
[mysqld]
datadir=${DATA}
port=${PORT}
bind-address=127.0.0.1
mysqlx=OFF
socket=${SOCK}
pid-file=${BASE}/mysqld.pid
log-error=${BASE}/mysqld.err
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
lower_case_table_names=2
max_allowed_packet=256M
innodb_buffer_pool_size=512M
EOF
}

write_plist() {
  mkdir -p "$(dirname "${PLIST}")"
  cat >"${PLIST}" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${MYSQLD}</string>
    <string>--defaults-file=${CNF}</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>WorkingDirectory</key><string>${BASE}</string>
  <key>StandardErrorPath</key><string>${BASE}/launchd.err</string>
</dict>
</plist>
EOF
}

write_secrets() {
  local pw="$1"
  if [[ ! -f "$SECRETS" ]]; then
    printf '# 本机库（./scripts/local-db.sh setup 生成，已 gitignore）。其余可选项见 db-secrets.local.example\n' >"$SECRETS"
  fi
  python3 - "$SECRETS" "${PORT}" "$pw" <<'PY'
import re, sys
path, port, pw = sys.argv[1:]
text = open(path, encoding="utf-8").read()
for key, val in (("HOME_DB_HOST", "127.0.0.1"), ("HOME_DB_PORT", port),
                 ("HOME_DB_USER", "root"), ("HOME_DB_PASSWORD", pw)):
    line = f"{key}={val}"
    if re.search(rf"^{key}=.*$", text, re.M):
        text = re.sub(rf"^{key}=.*$", line, text, flags=re.M)
    else:
        text += ("" if text.endswith("\n") else "\n") + line + "\n"
open(path, "w", encoding="utf-8").write(text)
PY
  chmod 600 "$SECRETS"
}

start() {
  mount_image
  write_cnf
  write_plist
  if running; then
    echo "已在运行：127.0.0.1:${PORT}"
    return 0
  fi
  launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "${PLIST}" 2>/dev/null || launchctl kickstart -k "gui/$(id -u)/${LABEL}"
  wait_ready
  echo "已启动：127.0.0.1:${PORT}（数据：${DATA}）"
}

stop() {
  launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
  if [[ -S "${SOCK}" ]]; then
    MYSQL_PWD="${HOME_DB_PASSWORD:-}" mysqladmin -S "${SOCK}" -uroot shutdown 2>/dev/null || true
  fi
  echo "已停止"
}

setup() {
  [[ -x "${MYSQLD}" ]] || { echo "没找到 ${MYSQLD}，先 brew install mysql" >&2; exit 1; }
  mount_image
  write_cnf
  write_plist
  local fresh=0
  if [[ -z "$(ls -A "${DATA}" 2>/dev/null)" ]]; then
    "${MYSQLD}" --defaults-file="${CNF}" --initialize-insecure
    fresh=1
  fi
  start
  if [[ "$fresh" == "1" ]]; then
    local pw
    pw="$(openssl rand -hex 16)"
    mysql -S "${SOCK}" -uroot <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '$pw';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$pw';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL
    write_secrets "$pw"
    HOME_DB_PASSWORD="$pw"
    echo "root 密码已写入 scripts/db-secrets.local（已 gitignore）"
  fi
  status
  echo "大文件目录（图片等）：${FILES_DIR}"
}

status() {
  if running; then
    echo "运行中：127.0.0.1:${PORT}（数据：${DATA}）"
    local_mysql -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}'" \
      | xargs -I{} echo "${DB_NAME} 表数量：{}"
  else
    echo "未运行。启动：./scripts/local-db.sh start（数据目录：${DATA}）"
  fi
  if [[ -d "${FILES_DIR}" ]]; then
    echo "大文件目录：${FILES_DIR}"
  else
    echo "大文件目录（外置盘未接）：${FILES_DIR}"
  fi
}

backup() {
  running || { echo "实例没在运行" >&2; exit 1; }
  mkdir -p "${BACKUP_DIR}"
  local out="${BACKUP_DIR}/${DB_NAME}-$(date +%Y%m%d-%H%M%S).sql"
  MYSQL_PWD="${HOME_DB_PASSWORD}" mysqldump -S "${SOCK}" -uroot --single-transaction --routines --triggers \
    --hex-blob --default-character-set=utf8mb4 --set-gtid-purged=OFF --databases "${DB_NAME}" >"$out"
  echo "已备份：$out"
}

import_dump() {
  local file="$1"
  [[ -f "$file" ]] || { echo "文件不存在：$file" >&2; exit 1; }
  running || { echo "实例没在运行" >&2; exit 1; }
  if [[ "$(local_mysql -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}'")" != "0" ]]; then
    backup
  fi
  local_mysql -e "DROP DATABASE IF EXISTS \`${DB_NAME}\`; CREATE DATABASE \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  local_mysql --default-character-set=utf8mb4 --database="${DB_NAME}" <"$file"
  echo "已导入：$file"
  status
}

pull_office() {
  if [[ -z "${OFFICE_DB_PASSWORD}" && -f "$YML" ]]; then
    OFFICE_DB_PASSWORD="$(python3 - "$YML" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'datasource:.*?password:\s*"?([^"\n]+)"?', text, re.S)
print(m.group(1).strip() if m else "")
PY
)"
  fi
  mkdir -p "${BACKUP_DIR}"
  local out="${BACKUP_DIR}/office-${DB_NAME}-$(date +%Y%m%d-%H%M%S).sql"
  echo "从公司库 ${OFFICE_DB_HOST}:${OFFICE_DB_PORT} 导出……"
  MYSQL_PWD="${OFFICE_DB_PASSWORD}" mysqldump -h "${OFFICE_DB_HOST}" -P "${OFFICE_DB_PORT}" -u "${OFFICE_DB_USER}" \
    --protocol=TCP --single-transaction --routines --triggers --hex-blob --default-character-set=utf8mb4 \
    --set-gtid-purged=OFF --column-statistics=0 "${DB_NAME}" >"$out"
  echo "已导出：$out"
  import_dump "$out"
}

case "${1:-status}" in
  setup) setup ;;
  status) status ;;
  start) start ;;
  stop) stop ;;
  mount) mount_image ;;
  unmount) unmount_image ;;
  backup) backup ;;
  import) import_dump "${2:?用法：./scripts/local-db.sh import <dump.sql>}" ;;
  pull-office) pull_office ;;
  *)
    echo "用法：./scripts/local-db.sh setup|status|start|stop|mount|unmount|backup|import <file>|pull-office" >&2
    exit 1
    ;;
esac
