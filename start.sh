#!/usr/bin/env bash
# =============================================================
#  qxfly-image-host 一键启动（Ubuntu / Debian 等 Linux）
#
#  除数据库外，运行环境全部取自项目根目录 runtime/：
#     runtime/linux-x64/jdk    便携 JDK 17
#     runtime/linux-x64/node   便携 Node 20 LTS
#  若尚未解压，会自动用 runtime/_cache 里的压缩包原生解压（首次运行一次）。
#
#  ./start.sh                     启动前后端（开发模式）
#  ./start.sh -Mode prod          单进程生产模式（界面由 jar 提供）
#  ./start.sh -Database h2        强制使用 H2 内存库
#  ./start.sh -Action status      查看状态
#  ./start.sh -Action stop        停止
#  ./start.sh -BackendPort 8081 -WebPort 5174   改端口（也可写进 config/local.env）
# =============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$ROOT/server"
WEB_DIR="$ROOT/web"
LOG_DIR="$ROOT/logs"
CONFIG_DIR="$ROOT/config"
RUNTIME_DIR="$ROOT/runtime"
CACHE_DIR="$RUNTIME_DIR/_cache"
ENV_FILE="$CONFIG_DIR/local.env"
BACKEND_JAR="$SERVER_DIR/target/qxfly-image-host.jar"
# 端口优先级：命令行参数 / 环境变量 > config/local.env > 默认 8080 / 5173
DEFAULT_BACKEND_PORT=8080
DEFAULT_WEB_PORT=5173
# 显式给的值（空 = 没给，交给 local.env / 默认值）
PRESET_BACKEND_PORT="${BACKEND_PORT:-}"
PRESET_WEB_PORT="${WEB_PORT:-}"
BACKEND_PORT="$DEFAULT_BACKEND_PORT"
WEB_PORT="$DEFAULT_WEB_PORT"
HEALTH_URL=""
# JVM 参数：环境变量 / config.local.env 里的 JAVA_OPTS 优先（单张无上限时全程流式，堆不用很大）
DEFAULT_JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC"
PRESET_JAVA_OPTS="${JAVA_OPTS:-}"
JAVA_OPTS=""

ACTION="start"
TARGET="all"
MODE="dev"
DATABASE="auto"
OPEN_BROWSER="yes"
BACKEND_PID=""
WEB_PID=""
EFFECTIVE_DB="mysql"

usage() { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
  case "$1" in
    -Action|--action)     ACTION="$2"; shift 2 ;;
    -Target|--target)     TARGET="$2"; shift 2 ;;
    -Mode|--mode)         MODE="$2"; shift 2 ;;
    -Database|--database) DATABASE="$2"; shift 2 ;;
    -BackendPort|--backend-port) PRESET_BACKEND_PORT="$2"; shift 2 ;;
    -WebPort|--web-port)         PRESET_WEB_PORT="$2"; shift 2 ;;
    -NoBrowser|--no-browser) OPEN_BROWSER="no"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1（用 -h 查看用法）" >&2; exit 1 ;;
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

banner() {
  echo
  printf '%s  ============================================================%s\n' "$C_CYAN" "$C_RESET"
  printf '%s     qxfly-image-host 多云端图床管理  ·  一键启动（Linux）%s\n' "$C_WHITE" "$C_RESET"
  printf '%s  ============================================================%s\n' "$C_CYAN" "$C_RESET"
}

# ------------------------------------------------------------ 便携运行时
extract_runtime() {
  local archive="$1" target="$2" expected="$3" label="$4"
  if [ -e "$target/$expected" ]; then
    ok "$label 已就绪"
    return 0
  fi
  if [ ! -f "$archive" ]; then
    warn "$label 缺失（$archive）"
    return 1
  fi
  step "解压 $label ..."
  local tmp="$target.tmp.$$"
  rm -rf "$tmp"
  mkdir -p "$tmp"
  tar -xf "$archive" -C "$tmp"
  local inner
  inner="$(find "$tmp" -mindepth 1 -maxdepth 1 | head -n 1)"
  mkdir -p "$(dirname "$target")"
  rm -rf "$target"
  mv "$inner" "$target"
  rm -rf "$tmp"
  ok "$label -> $target"
}

ensure_runtime() {
  local need_node="$1"
  extract_runtime "$CACHE_DIR/jdk-linux-x64.tar.gz" "$RUNTIME_DIR/linux-x64/jdk" "bin/java" "便携 JDK 17" || true
  if [ "$need_node" = "yes" ]; then
    extract_runtime "$CACHE_DIR/node-linux-x64.tar.xz" "$RUNTIME_DIR/linux-x64/node" "bin/node" "便携 Node 20" || true
  fi

  if [ -x "$RUNTIME_DIR/linux-x64/jdk/bin/java" ]; then
    export JAVA_HOME="$RUNTIME_DIR/linux-x64/jdk"
    export PATH="$JAVA_HOME/bin:$PATH"
    info "Java   : $("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
  else
    warn '未找到便携 JDK，改用系统 java'
    need_cmd java "请安装 JDK 17+（sudo apt install openjdk-17-jdk）"
    info "Java   : $(java -version 2>&1 | head -n 1)"
  fi

  if [ -x "$RUNTIME_DIR/linux-x64/node/bin/node" ]; then
    export PATH="$RUNTIME_DIR/linux-x64/node/bin:$PATH"
    info "Node   : $("$RUNTIME_DIR/linux-x64/node/bin/node" -v)"
    NPM_CMD="$RUNTIME_DIR/linux-x64/node/bin/npm"
  else
    need_cmd npm "请安装 Node.js 18+（sudo apt install nodejs npm）"
    NPM_CMD="npm"
  fi
}

# ------------------------------------------------------------ 网络与进程
need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "未找到 $1。$2"
    exit 1
  fi
}

port_open() {
  local port="$1"
  if command -v nc >/dev/null 2>&1; then
    nc -z 127.0.0.1 "$port" >/dev/null 2>&1
    return $?
  fi
  (exec 3<>"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
}

http_get() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsS --max-time 5 "$url" 2>/dev/null
    return $?
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -O - --timeout=5 "$url" 2>/dev/null
    return $?
  fi
  return 1
}

wait_health() {
  local waited=0
  while [ "$waited" -lt 180 ]; do
    if http_get "$HEALTH_URL" | grep -q '"status":"UP"'; then
      return 0
    fi
    printf '\r  [*] 等待后端就绪 ... %ss' "$waited"
    waited=$((waited + 2))
    sleep 2
  done
  echo
  return 1
}

wait_http() {
  local waited=0
  while [ "$waited" -lt 120 ]; do
    if port_open "$WEB_PORT"; then return 0; fi
    waited=$((waited + 2))
    sleep 2
  done
  return 1
}

port_pids() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
  elif command -v fuser >/dev/null 2>&1; then
    fuser "$port"/tcp 2>/dev/null | tr -s ' ' '\n' || true
  elif command -v ss >/dev/null 2>&1; then
    ss -lptn "sport = :$port" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 || true
  fi
}

kill_pid() {
  local pid="$1" name="$2"
  if [ -z "$pid" ]; then return; fi
  if ! kill -0 "$pid" 2>/dev/null; then return; fi
  step "停止 $name (PID $pid)"
  pkill -P "$pid" 2>/dev/null || true
  kill "$pid" 2>/dev/null || true
  sleep 1
  kill -9 "$pid" 2>/dev/null || true
}

stop_port() {
  local port="$1" name="$2" pids pid waited
  pids="$(port_pids "$port")"
  if [ -z "$pids" ]; then
    info "$name 未在运行（端口 $port 空闲）"
    return
  fi
  for pid in $pids; do kill_pid "$pid" "$name"; done
  waited=0
  while [ "$waited" -lt 15 ] && port_open "$port"; do
    sleep 1
    waited=$((waited + 1))
  done
}

cleanup() {
  echo
  step '正在清理由本脚本启动的进程 ...'
  kill_pid "$WEB_PID" '前端服务'
  kill_pid "$BACKEND_PID" '后端服务'
  stop_port "$BACKEND_PORT" '后端服务'
  stop_port "$WEB_PORT" '前端服务'
  ok '已退出'
}

# ------------------------------------------------------------ 数据库
load_local_env() {
  if [ ! -f "$ENV_FILE" ]; then return 1; fi
  while IFS= read -r line; do
    case "$line" in
      \#*|'') continue ;;
    esac
    key="$(printf '%s' "$line" | cut -d= -f1 | tr -d ' ')"
    value="$(printf '%s' "$line" | cut -d= -f2-)"
    case "$key" in
      MYSQL_HOST|MYSQL_PORT|MYSQL_DATABASE|MYSQL_USER|MYSQL_PASSWORD) export "$key=$value" ;;
      BACKEND_PORT) BACKEND_PORT="$value" ;;
      WEB_PORT) WEB_PORT="$value" ;;
      JAVA_OPTS) JAVA_OPTS="$value" ;;
    esac
  done < "$ENV_FILE"
  return 0
}

# 端口最终确定：命令行里显式给的优先，其次 local.env，最后默认值
resolve_ports() {
  if [ -n "$PRESET_BACKEND_PORT" ]; then BACKEND_PORT="$PRESET_BACKEND_PORT"; fi
  if [ -n "$PRESET_WEB_PORT" ]; then WEB_PORT="$PRESET_WEB_PORT"; fi
  case "$BACKEND_PORT" in ''|*[!0-9]*) BACKEND_PORT="$DEFAULT_BACKEND_PORT" ;; esac
  case "$WEB_PORT" in ''|*[!0-9]*) WEB_PORT="$DEFAULT_WEB_PORT" ;; esac
  HEALTH_URL="http://127.0.0.1:$BACKEND_PORT/actuator/health"
  # 传给子进程：后端读 SERVER_PORT（application.yml），Vite 读 WEB_PORT / BACKEND_PORT（vite.config.ts）
  export SERVER_PORT="$BACKEND_PORT"
  export BACKEND_PORT
  export WEB_PORT
  # JVM 参数：显式给的 > local.env > 默认
  if [ -n "$PRESET_JAVA_OPTS" ]; then JAVA_OPTS="$PRESET_JAVA_OPTS"; fi
  if [ -z "$JAVA_OPTS" ]; then JAVA_OPTS="$DEFAULT_JAVA_OPTS"; fi
  export JAVA_OPTS
}

resolve_database() {
  if [ "$DATABASE" != "auto" ]; then
    info "按参数指定使用 $DATABASE 模式"
    return 0
  fi
  step '探测本地 MySQL ...'
  if port_open 3306; then
    ok '检测到 MySQL（127.0.0.1:3306），使用 MySQL 模式'
    DATABASE="mysql"
    return 0
  fi
  info '127.0.0.1:3306 上没有可用的 MySQL'
  warn '自动降级为 H2 内存库：功能可完整体验，但重启后数据清空'
  info '要持久化：sudo ./mysql.sh install 后执行 ./mysql.sh init'
  DATABASE="h2"
}

mysql_service_name() {
  for name in mysql mysqld mariadb; do
    if command -v systemctl >/dev/null 2>&1 && systemctl list-unit-files "$name.service" >/dev/null 2>&1; then
      echo "$name"
      return 0
    fi
  done
  echo "mysql"
}

# ------------------------------------------------------------ 后端
need_rebuild() {
  if [ ! -f "$BACKEND_JAR" ]; then return 0; fi
  if [ "$MODE" = "prod" ]; then
    if ! unzip -l "$BACKEND_JAR" 2>/dev/null | grep -q 'BOOT-INF/classes/static/index.html'; then
      return 0
    fi
  fi
  if [ -n "$(find "$SERVER_DIR/src" "$SERVER_DIR/pom.xml" -newer "$BACKEND_JAR" -print -quit 2>/dev/null)" ]; then
    return 0
  fi
  return 1
}

build_backend() {
  local mvn="$SERVER_DIR/mvnw"
  if [ ! -f "$mvn" ]; then fail "找不到 Maven 包装器：$mvn"; exit 1; fi
  chmod +x "$mvn" 2>/dev/null || true

  if [ "$MODE" = "prod" ]; then
    step '生产模式：构建前端并打进 jar ...'
    (cd "$WEB_DIR" && "$NPM_CMD" install --no-audit --no-fund && "$NPM_CMD" run build)
    step '编译后端（首次会下载 Maven 与依赖）...'
    (cd "$SERVER_DIR" && "$mvn" -B -DskipTests -Pwith-web package)
  else
    step '编译后端（首次会下载 Maven 与依赖）...'
    (cd "$SERVER_DIR" && "$mvn" -B -DskipTests package)
  fi
  ok '后端编译完成'
}

start_backend() {
  local extra=""
  if port_open "$BACKEND_PORT"; then
    warn "端口 $BACKEND_PORT 已被占用，复用已在运行的后端"
    return 0
  fi
  if need_rebuild; then build_backend; else ok '后端产物已是最新（源码无变更，跳过编译）'; fi

  mkdir -p "$LOG_DIR"
  if [ "$DATABASE" = "h2" ]; then extra="--spring.profiles.active=h2"; fi

  step "启动后端（$DATABASE 模式，端口 $BACKEND_PORT）..."
  # JAVA_OPTS 有意不加引号：里面的多个参数要按空格拆开传给 java
  (cd "$SERVER_DIR" && exec java $JAVA_OPTS -Dfile.encoding=UTF-8 -jar "$BACKEND_JAR" $extra) >"$LOG_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  info "日志 : $LOG_DIR/backend.log"

  if wait_health; then
    echo
    ok '后端健康检查通过'
    EFFECTIVE_DB="$DATABASE"
  else
    fail '后端启动失败'
    tail -n 12 "$LOG_DIR/backend.log" 2>/dev/null | while IFS= read -r l; do info "$l"; done
    if [ "$DATABASE" = "mysql" ] && [ "$MODE" != "mysql" ]; then
      warn '自动改用 H2 内存库重试 ...'
      DATABASE="h2"
      start_backend
      return $?
    fi
    exit 1
  fi
}

# ------------------------------------------------------------ 前端
install_web_deps() {
  if [ -d "$WEB_DIR/node_modules" ]; then return; fi
  step '安装前端依赖（首次运行需要几分钟）...'
  (cd "$WEB_DIR" && "$NPM_CMD" install --no-audit --no-fund)
  ok '前端依赖安装完成'
}

start_frontend() {
  install_web_deps
  if port_open "$WEB_PORT"; then
    warn "端口 $WEB_PORT 已被占用，复用已在运行的前端"
    return 0
  fi
  if ! port_open "$BACKEND_PORT"; then
    warn '后端未在运行：页面能打开，但登录与上传会失败'
  fi
  mkdir -p "$LOG_DIR"
  step "启动前端开发服务器（端口 $WEB_PORT）..."
  (cd "$WEB_DIR" && exec "$NPM_CMD" run dev -- --port "$WEB_PORT") >"$LOG_DIR/web.log" 2>&1 &
  WEB_PID=$!
  info "日志 : $LOG_DIR/web.log"
  if wait_http; then
    ok '前端已就绪'
  else
    fail '前端未在 120 秒内就绪，请查看 logs/web.log'
    exit 1
  fi
}

show_status() {
  echo
  local backend_pids web_pids health
  backend_pids="$(port_pids "$BACKEND_PORT")"
  web_pids="$(port_pids "$WEB_PORT")"
  if [ -n "$backend_pids" ]; then
    health="DOWN"
    if http_get "$HEALTH_URL" | grep -q '"status":"UP"'; then health="UP"; fi
    printf '%s  后端  : 运行中   端口 %s   PID %s   健康 %s%s\n' "$C_GREEN" "$BACKEND_PORT" "$(echo "$backend_pids" | tr '\n' ',')" "$health" "$C_RESET"
  else
    printf '%s  后端  : 未运行%s\n' "$C_GRAY" "$C_RESET"
  fi
  if [ -n "$web_pids" ]; then
    printf '%s  前端  : 运行中   端口 %s   %s\n' "$C_GREEN" "$WEB_PORT" "$C_RESET"
  else
    printf '%s  前端  : 未运行%s\n' "$C_GRAY" "$C_RESET"
  fi
  if port_open 3306; then
    printf '%s  MySQL : 可连接   127.0.0.1:3306%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%s  MySQL : 不可连接%s\n' "$C_GRAY" "$C_RESET"
  fi
  if [ -x "$RUNTIME_DIR/linux-x64/jdk/bin/java" ]; then
    printf '%s  运行时 : 使用项目自带 runtime/linux-x64%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%s  运行时 : 使用系统 JDK / Node%s\n' "$C_GRAY" "$C_RESET"
  fi
  if [ -f "$ENV_FILE" ]; then
    printf '%s  数据库 : 已读取 config/local.env%s\n' "$C_GREEN" "$C_RESET"
  else
    printf '%s  数据库 : 无 config/local.env%s\n' "$C_GRAY" "$C_RESET"
  fi
  echo
}

show_summary() {
  echo
  printf '%s  ------------------------------------------------------------%s\n' "$C_CYAN" "$C_RESET"
  printf '%s   后端接口 : http://127.0.0.1:%s    [数据库 %s]%s\n' "$C_WHITE" "$BACKEND_PORT" "$EFFECTIVE_DB" "$C_RESET"
  if [ "$MODE" = "prod" ]; then
    printf '%s   管理界面 : http://127.0.0.1:%s   （单进程，界面已打进 jar）%s\n' "$C_WHITE" "$BACKEND_PORT" "$C_RESET"
  else
    printf '%s   管理界面 : http://localhost:%s%s\n' "$C_WHITE" "$WEB_PORT" "$C_RESET"
  fi
  printf '%s   默认账号 : admin / admin123   （登录后请立即修改密码）%s\n' "$C_YELLOW" "$C_RESET"
  printf '%s  ------------------------------------------------------------%s\n' "$C_CYAN" "$C_RESET"
  printf '%s   停止服务 : Ctrl+C   或   ./start.sh -Action stop%s\n' "$C_GRAY" "$C_RESET"
  printf '%s   查看日志 : logs/backend.log   logs/web.log%s\n' "$C_GRAY" "$C_RESET"
  echo
}

# ============================================================ 主流程
banner

if [ ! -f "$SERVER_DIR/pom.xml" ] || [ ! -f "$WEB_DIR/package.json" ]; then
  fail "当前目录不像是 qxfly-image-host 项目根目录：$ROOT"
  exit 1
fi

mkdir -p "$LOG_DIR"

if [ "$MODE" = "prod" ] && [ "$TARGET" = "all" ]; then TARGET="backend"; fi

# 端口要在 status / stop / start 之前定好
if load_local_env; then ok '已加载配置 config/local.env'; fi
resolve_ports
info "后端端口 $BACKEND_PORT，前端端口 $WEB_PORT（改 config/local.env 或用 -BackendPort / -WebPort）"

case "$ACTION" in
  status)
    show_status
    exit 0
    ;;
  stop)
    step '停止已有服务 ...'
    stop_port "$BACKEND_PORT" '后端服务'
    stop_port "$WEB_PORT" '前端服务'
    ok '已停止'
    info 'MySQL 服务保持运行，如需停止：sudo ./mysql.sh stop'
    exit 0
    ;;
  restart)
    step '停止已有服务 ...'
    stop_port "$BACKEND_PORT" '后端服务'
    stop_port "$WEB_PORT" '前端服务'
    ok '已停止'
    ;;
  start) ;;
  *) fail "未知的 -Action：$ACTION"; exit 1 ;;
esac

need_node="no"
if [ "$MODE" = "dev" ] && { [ "$TARGET" = "all" ] || [ "$TARGET" = "web" ] || [ "$TARGET" = "desktop" ]; }; then
  need_node="yes"
fi
ensure_runtime "$need_node"

if [ "$TARGET" = "backend" ] || [ "$TARGET" = "all" ]; then
  resolve_database
fi

trap cleanup EXIT INT TERM

if [ "$TARGET" = "all" ]; then
  start_backend
  start_frontend
  show_summary
  if [ "$OPEN_BROWSER" = "yes" ]; then
    if command -v xdg-open >/dev/null 2>&1; then xdg-open "http://localhost:$WEB_PORT" >/dev/null 2>&1 || true; fi
  fi
  printf '%s  服务运行中，按 Ctrl+C 结束%s\n\n' "$C_CYAN" "$C_RESET"
  while true; do
    sleep 3
    if ! port_open "$BACKEND_PORT"; then fail '检测到后端已退出，请查看 logs/backend.log'; break; fi
    if ! port_open "$WEB_PORT"; then fail '检测到前端已退出，请查看 logs/web.log'; break; fi
  done
elif [ "$TARGET" = "backend" ]; then
  if [ "$MODE" = "prod" ]; then
    show_summary
    trap - EXIT INT TERM
    start_backend
  else
    trap - EXIT INT TERM
    show_summary
    start_backend
  fi
else
  trap - EXIT INT TERM
  start_frontend
fi
