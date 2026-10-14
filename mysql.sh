#!/usr/bin/env bash
# =============================================================
#  qxfly-image-host 数据库管家（Ubuntu / Debian）
#
#  ./mysql.sh status                 查看服务状态与连接配置
#  ./mysql.sh install                安装 MySQL 并设为开机自启
#  ./mysql.sh start|stop|restart
#  ./mysql.sh autostart on|off
#  ./mysql.sh init -u root -p 密码   初始化业务库并写 config/local.env
#  ./mysql.sh console                打开 mysql 命令行
# =============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
CONFIG_DIR="$ROOT/config"
ENV_FILE="$CONFIG_DIR/local.env"

ACTION="status"
VALUE=""
DB_USER="root"
DB_PASSWORD=""
DATABASE="pichost"
APP_USER="pichost"
APP_PASSWORD=""

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    -u|--user)     DB_USER="$2"; shift 2 ;;
    -p|--password) DB_PASSWORD="$2"; shift 2 ;;
    -d|--database) DATABASE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      if [ "$ACTION" = "status" ]; then ACTION="$1"; else VALUE="$1"; fi
      shift
      ;;
  esac
done

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_CYAN=$'\033[36m'; C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_GRAY=$'\033[90m'; C_WHITE=$'\033[97m'
else
  C_RESET=""; C_CYAN=""; C_GREEN=""; C_YELLOW=""; C_RED=""; C_GRAY=""; C_WHITE=""
fi

step() { printf '%s  [*]%s %s\n' "$C_CYAN" "$C_RESET" "$1"; }
ok()   { printf '%s  [+]%s %s\n' "$C_GREEN" "$C_RESET" "$1"; }
warn() { printf '%s  [!]%s %s\n' "$C_YELLOW" "$C_RESET" "$1"; }
fail() { printf '%s  [x]%s %s\n' "$C_RED" "$C_RESET" "$1" >&2; }
info() { printf '%s      %s%s\n' "$C_GRAY" "$1" "$C_RESET"; }

sudo_cmd() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo >/dev/null 2>&1; then
    sudo "$@"
  else
    fail '需要 root 权限，且未找到 sudo'
    exit 1
  fi
}

detect_service() {
  local name
  for name in mysql mysqld mariadb; do
    if systemctl list-unit-files "$name.service" 2>/dev/null | grep -q "^$name.service"; then
      echo "$name"
      return 0
    fi
  done
  echo "mysql"
}

SERVICE="$(detect_service)"

has_mysql_client() { command -v mysql >/dev/null 2>&1; }

run_sql() {
  local sql="$1" user="$2" pass="$3"
  local db=""
  if [ "$#" -ge 4 ]; then db="$4"; fi
  if [ -n "$db" ]; then
    MYSQL_PWD="$pass" mysql -u "$user" --connect-timeout=8 -N -B "$db" -e "$sql" 2>&1
  else
    MYSQL_PWD="$pass" mysql -u "$user" --connect-timeout=8 -N -B -e "$sql" 2>&1
  fi
}

show_status() {
  echo
  printf '%s  ============================================================%s\n' "$C_CYAN" "$C_RESET"
  printf '%s     qxfly-image-host 数据库状态（Linux）%s\n' "$C_WHITE" "$C_RESET"
  printf '%s  ============================================================%s\n' "$C_CYAN" "$C_RESET"
  printf '  服务名称 : %s\n' "$SERVICE"
  if systemctl list-unit-files "$SERVICE.service" >/dev/null 2>&1; then
    printf '  运行状态 : %s\n' "$(systemctl is-active "$SERVICE" 2>/dev/null || true)"
    printf '  开机自启 : %s\n' "$(systemctl is-enabled "$SERVICE" 2>/dev/null || true)"
  else
    printf '%s  未安装 systemd 服务（可执行 sudo ./mysql.sh install）%s\n' "$C_YELLOW" "$C_RESET"
  fi
  if command -v ss >/dev/null 2>&1 && ss -lnt 2>/dev/null | grep -q ':3306'; then
    printf '%s  端口监听 : 3306 已监听%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%s  端口监听 : 3306 未监听%s\n' "$C_YELLOW" "$C_RESET"
  fi
  if has_mysql_client; then
    printf '%s  客户端   : %s%s\n' "$C_GREEN" "$(command -v mysql)" "$C_RESET"
  else
    printf '%s  客户端   : mysql 未安装%s\n' "$C_YELLOW" "$C_RESET"
  fi
  if [ -f "$ENV_FILE" ]; then
    printf '%s  连接配置 : %s%s\n' "$C_GREEN" "$ENV_FILE" "$C_RESET"
    local u p d
    u="$(grep '^MYSQL_USER=' "$ENV_FILE" | cut -d= -f2-)"
    p="$(grep '^MYSQL_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)"
    d="$(grep '^MYSQL_DATABASE=' "$ENV_FILE" | cut -d= -f2-)"
    if [ -n "$u" ] && run_sql 'SELECT 1;' "$u" "$p" "$d" >/dev/null 2>&1; then
      printf '%s  连接测试 : 通过（账号 %s，库 %s）%s\n' "$C_GREEN" "$u" "$d" "$C_RESET"
    else
      printf '%s  连接测试 : 失败%s\n' "$C_RED" "$C_RESET"
    fi
  else
    printf '%s  连接配置 : 尚未生成（执行 ./mysql.sh init -p 密码）%s\n' "$C_YELLOW" "$C_RESET"
  fi
  echo
}

do_install() {
  if systemctl list-unit-files "$SERVICE.service" >/dev/null 2>&1; then
    ok "服务已存在：$SERVICE，跳过安装"
  else
    step '安装 MySQL Server（apt）...'
    sudo_cmd apt-get update
    sudo_cmd env DEBIAN_FRONTEND=noninteractive apt-get install -y mysql-server
  fi
  step '设置开机自启并启动 ...'
  sudo_cmd systemctl enable "$SERVICE" >/dev/null
  sudo_cmd systemctl start "$SERVICE"
  sleep 2
  ok "服务 $SERVICE：$(systemctl is-active "$SERVICE") / $(systemctl is-enabled "$SERVICE")"
}

do_autostart() {
  if [ "$VALUE" = "off" ]; then
    sudo_cmd systemctl disable "$SERVICE" >/dev/null
  else
    sudo_cmd systemctl enable "$SERVICE" >/dev/null
  fi
  ok "开机自启：$(systemctl is-enabled "$SERVICE" 2>/dev/null || echo unknown)"
}

do_init() {
  if ! has_mysql_client; then
    fail '未找到 mysql 客户端，请先执行 sudo ./mysql.sh install'
    return 1
  fi
  if [ -z "$DB_PASSWORD" ]; then
    fail '请提供数据库口令，例如：./mysql.sh init -u root -p 你的密码'
    return 1
  fi

  step '测试数据库连接 ...'
  local version
  if ! version="$(run_sql 'SELECT VERSION();' "$DB_USER" "$DB_PASSWORD")"; then
    fail '连接失败，请确认口令是否正确'
    info "$version"
    return 1
  fi
  ok "连接成功，MySQL 版本 $version"

  if [ -z "$APP_PASSWORD" ]; then
    APP_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
  fi

  step "创建业务库 $DATABASE 与账号 $APP_USER ..."
  local sql
  sql="CREATE DATABASE IF NOT EXISTS $DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
  sql="$sql CREATE USER IF NOT EXISTS '$APP_USER'@'%' IDENTIFIED BY '$APP_PASSWORD';"
  sql="$sql ALTER USER '$APP_USER'@'%' IDENTIFIED BY '$APP_PASSWORD';"
  sql="$sql CREATE USER IF NOT EXISTS '$APP_USER'@'localhost' IDENTIFIED BY '$APP_PASSWORD';"
  sql="$sql ALTER USER '$APP_USER'@'localhost' IDENTIFIED BY '$APP_PASSWORD';"
  sql="$sql GRANT ALL PRIVILEGES ON $DATABASE.* TO '$APP_USER'@'%';"
  sql="$sql GRANT ALL PRIVILEGES ON $DATABASE.* TO '$APP_USER'@'localhost';"
  sql="$sql FLUSH PRIVILEGES;"
  if ! run_sql "$sql" "$DB_USER" "$DB_PASSWORD" >/dev/null; then
    fail '创建业务库失败'
    return 1
  fi
  ok '业务库与账号已就绪'

  mkdir -p "$CONFIG_DIR"
  {
    printf '# qxfly-image-host 数据库连接配置（由 mysql.sh 生成，已被 .gitignore 忽略）\n'
    printf 'MYSQL_HOST=127.0.0.1\n'
    printf 'MYSQL_PORT=3306\n'
    printf 'MYSQL_DATABASE=%s\n' "$DATABASE"
    printf 'MYSQL_USER=%s\n' "$APP_USER"
    printf 'MYSQL_PASSWORD=%s\n' "$APP_PASSWORD"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  ok "连接配置已写入：$ENV_FILE"
  echo
  info '现在可以启动系统了：./start.sh'
}

do_console() {
  if ! has_mysql_client; then fail '未找到 mysql 客户端'; return 1; fi
  info '进入 mysql 命令行，输入 exit 退出'
  if [ -n "$DB_PASSWORD" ]; then
    MYSQL_PWD="$DB_PASSWORD" mysql -u "$DB_USER"
  else
    mysql -u "$DB_USER" -p
  fi
}

case "$ACTION" in
  status)    show_status ;;
  install)   do_install ;;
  start)     sudo_cmd systemctl start "$SERVICE"; sleep 1; ok "服务状态：$(systemctl is-active "$SERVICE")" ;;
  stop)      sudo_cmd systemctl stop "$SERVICE"; ok '已停止' ;;
  restart)   sudo_cmd systemctl restart "$SERVICE"; sleep 2; ok "服务状态：$(systemctl is-active "$SERVICE")" ;;
  autostart) do_autostart ;;
  init)      do_init ;;
  console)   do_console ;;
  *)         fail "未知动作：$ACTION"; usage; exit 1 ;;
esac
