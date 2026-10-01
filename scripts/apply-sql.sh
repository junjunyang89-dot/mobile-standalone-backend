#!/usr/bin/env bash
# 把 SQL 打到家里库、公司库，或两边都打。已执行过的文件会记在 schema_sql_applied，不会重跑。
#
#   ./scripts/apply-sql.sh              # 两边都打未执行的 migration_*.sql
#   ./scripts/apply-sql.sh both
#   ./scripts/apply-sql.sh home
#   ./scripts/apply-sql.sh office
#   ./scripts/apply-sql.sh both path/to/new.sql
#   ./scripts/apply-sql.sh init-home    # 本机建库 + schema + 全部 migration
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB_DIR="$ROOT/src/main/resources/db"
SECRETS="$ROOT/scripts/db-secrets.local"
YML="$ROOT/src/main/resources/application.yml"

HOME_DB_HOST=127.0.0.1
HOME_DB_PORT=3306
HOME_DB_USER=root
HOME_DB_PASSWORD=
OFFICE_DB_HOST=118.25.144.133
OFFICE_DB_PORT=13306
OFFICE_DB_USER=root
OFFICE_DB_PASSWORD=
DB_NAME=mobile_standalone

if [[ -f "$SECRETS" ]]; then
  # shellcheck disable=SC1090
  source "$SECRETS"
fi

if [[ -z "${OFFICE_DB_PASSWORD}" && -f "$YML" ]]; then
  OFFICE_DB_PASSWORD="$(
    python3 - "$YML" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
block = re.search(r"datasource:\s*\n(?:.*\n){0,20}?", text)
chunk = text[block.start(): block.start() + 800] if block else text
m = re.search(r'password:\s*"([^"]+)"', chunk)
print(m.group(1) if m else "")
PY
  )"
fi

need_mysql() {
  command -v mysql >/dev/null 2>&1 || {
    echo "本机没有 mysql 客户端。macOS：brew install mysql-client && 把 mysql 加到 PATH" >&2
    exit 1
  }
}

mysql_cmd() {
  local side="$1"
  if [[ "$side" == "home" ]]; then
    MYSQL_PWD="$HOME_DB_PASSWORD" mysql -h "$HOME_DB_HOST" -P "$HOME_DB_PORT" -u "$HOME_DB_USER" --protocol=TCP
  else
    MYSQL_PWD="$OFFICE_DB_PASSWORD" mysql -h "$OFFICE_DB_HOST" -P "$OFFICE_DB_PORT" -u "$OFFICE_DB_USER" --protocol=TCP
  fi
}

run_sql() {
  local side="$1"
  local file="$2"
  echo "→ ${side}: $(basename "$file")"
  mysql_cmd "$side" --database="$DB_NAME" < "$file"
}

ensure_db() {
  local side="$1"
  mysql_cmd "$side" -e "CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  mysql_cmd "$side" --database="$DB_NAME" -e \
    "CREATE TABLE IF NOT EXISTS schema_sql_applied (
       filename VARCHAR(255) PRIMARY KEY,
       applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;"
}

already_applied() {
  local side="$1"
  local name="$2"
  local n
  n="$(mysql_cmd "$side" --database="$DB_NAME" -N -e \
    "SELECT COUNT(*) FROM schema_sql_applied WHERE filename='${name}';")"
  [[ "${n// /}" == "1" ]]
}

mark_applied() {
  local side="$1"
  local name="$2"
  mysql_cmd "$side" --database="$DB_NAME" -e \
    "INSERT INTO schema_sql_applied (filename) VALUES ('${name}') ON DUPLICATE KEY UPDATE applied_at=CURRENT_TIMESTAMP;"
}

apply_file() {
  local side="$1"
  local file="$2"
  local name
  name="$(basename "$file")"
  if already_applied "$side" "$name"; then
    echo "跳过 ${side}（已执行）：${name}"
    return 0
  fi
  run_sql "$side" "$file"
  mark_applied "$side" "$name"
}

pending_migrations() {
  ls -1 "$DB_DIR"/migration_*.sql 2>/dev/null | sort
}

apply_pending() {
  local side="$1"
  ensure_db "$side"
  local f
  for f in $(pending_migrations); do
    apply_file "$side" "$f"
  done
}

init_home() {
  ensure_db home
  apply_file home "$DB_DIR/schema.sql"
  local extra
  for extra in \
    "$DB_DIR/schema_ui_assets.sql" \
    "$DB_DIR/schema_test_automation.sql" \
    "$DB_DIR/schema_online_regression.sql" \
    "$DB_DIR/schema_api_test.sql" \
    "$DB_DIR/schema_api_projects.sql" \
    "$DB_DIR/schema_api_change.sql" \
    "$DB_DIR/schema_web_test_cases.sql" \
    "$DB_DIR/schema_agent_storage.sql" \
    "$DB_DIR/schema_engineering_memory.sql" \
    "$DB_DIR/schema_knowledge_handbook.sql" \
    "$DB_DIR/schema_debug_api_tests.sql" \
    "$DB_DIR/schema_ui_h5_pages.sql"
  do
    [[ -f "$extra" ]] && apply_file home "$extra"
  done
  apply_pending home
  echo "本机库已就绪。接着：./scripts/use-db.sh home 然后重启后端。"
}

need_mysql

cmd="${1:-both}"
shift || true

case "$cmd" in
  init-home)
    init_home
    ;;
  home|office)
    if [[ -n "${1:-}" ]]; then
      ensure_db "$cmd"
      apply_file "$cmd" "$1"
    else
      apply_pending "$cmd"
    fi
    ;;
  both)
    if [[ -n "${1:-}" ]]; then
      ensure_db home
      apply_file home "$1"
      ensure_db office
      apply_file office "$1"
    else
      echo "== 家里库 =="
      apply_pending home
      echo "== 公司库 =="
      apply_pending office
    fi
    ;;
  *)
    echo "用法：./scripts/apply-sql.sh [both|home|office|init-home] [某个.sql]" >&2
    exit 1
    ;;
esac
