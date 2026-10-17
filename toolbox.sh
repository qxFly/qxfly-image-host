#!/usr/bin/env bash
# =============================================================
#  qxfly-image-host 工具箱（Ubuntu / Debian）
#  与 Windows 侧 toolbox.cmd 对应；不带参数进菜单，带参数走子命令。
#
#    ./toolbox.sh status | start | stop | restart   启动相关（转发给 ./start.sh）
#    ./toolbox.sh db status|install|start|stop|init|console   数据库管家（转发给 ./mysql.sh）
#    ./toolbox.sh diag        一键诊断（端口/环境/产物/配置，产出 logs/diag-*.txt）
#    ./toolbox.sh clean       清理缓存与产物（先算体积再确认）
#    ./toolbox.sh config      查看生效配置与访问地址
#    ./toolbox.sh logs        查看最新日志
#    ./toolbox.sh deploy      使用 deploy/ 里的 nginx 与 systemd 模板
#    ./toolbox.sh check       脚本自检（.cmd 纯 ASCII、.ps1 有 UTF-8 BOM）
# =============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$ROOT/config/local.env"
LOG_DIR="$ROOT/logs"

c_cyan=$'\033[36m'; c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_off=$'\033[0m'
step() { printf '  %s[*]%s %s\n' "$c_cyan" "$c_off" "$1"; }
ok()   { printf '  %s[+]%s %s\n' "$c_green" "$c_off" "$1"; }
warn() { printf '  %s[!]%s %s\n' "$c_yellow" "$c_off" "$1"; }
fail() { printf '  %s[x]%s %s\n' "$c_red" "$c_off" "$1"; }
info() { printf '      %s\n' "$1"; }
banner() { printf '\n  ============================================================\n     %s\n  ============================================================\n' "$1"; }

env_get() {
  [ -f "$ENV_FILE" ] || return 0
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$ENV_FILE" | tail -n1 | tr -d '\r'
}

backend_port() {
  local p; p="$(env_get BACKEND_PORT)"
  # 兼容 SERVER_PORT（旧模板里用的名字）
  [ -n "$p" ] || p="$(env_get SERVER_PORT)"
  echo "${p:-8080}"
}

dir_size_mb() {
  [ -e "$1" ] || { echo 0; return; }
  du -sm "$1" 2>/dev/null | awk '{print $1}'
}

show_config() {
  banner '当前生效配置'
  info "配置文件：$ENV_FILE"
  echo
  for k in BACKEND_PORT WEB_PORT JAVA_OPTS PUBLIC_BASE_URL LOCAL_STORAGE_DIR THUMB_CACHE_DIR MYSQL_HOST MYSQL_PORT MYSQL_DATABASE MYSQL_USER SERVER_ADDRESS; do
    v="$(env_get "$k")"
    printf '    %-24s %s\n' "$k" "${v:-(未设置)}"
  done
  local port; port="$(backend_port)"
  echo
  ok "后端访问地址： http://127.0.0.1:$port"
  info "公网/局域网： http://<服务器IP>:$port   （反代时把 PUBLIC_BASE_URL 设为对外地址）"
}

show_logs() {
  banner '日志'
  [ -d "$LOG_DIR" ] || { warn '还没有 logs 目录'; return; }
  local newest
  newest="$(ls -1t "$LOG_DIR"/*.log 2>/dev/null | head -n1 || true)"
  [ -n "$newest" ] || { warn 'logs 目录里还没有日志文件'; return; }
  echo
  ls -lht "$LOG_DIR"/*.log 2>/dev/null | awk 'NR<=10 {printf "    %s  %s  %s\n", $5, $6" "$7" "$8, $9}'
  echo
  ok "最近 40 行：$(basename "$newest")"
  tail -n 40 "$newest" | sed 's/^/    /'
}

diag() {
  banner '一键诊断'
  mkdir -p "$LOG_DIR"
  local out="$LOG_DIR/diag-$(date +%Y%m%d-%H%M%S).txt"
  {
    echo "qxfly-image-host 诊断报告  $(date '+%Y-%m-%d %H:%M:%S')"
    echo
    echo '== 环境 =='
    echo "uname      : $(uname -a)"
    if [ -x "$ROOT/runtime/linux-x64/jdk/bin/java" ]; then
      echo "JDK        : 便携 $ROOT/runtime/linux-x64/jdk"
      echo "JDK 版本   : $("$ROOT/runtime/linux-x64/jdk/bin/java" -version 2>&1 | head -n1)"
    else
      echo 'JDK        : 未找到便携 JDK（将回退系统 java）'
    fi
    command -v node >/dev/null 2>&1 && echo "Node       : $(node -v)" || echo 'Node       : 无（生产模式不需要）'
    echo
    echo '== 端口 =='
    if command -v ss >/dev/null 2>&1; then
      ss -lntp 2>/dev/null | grep -E ":$(backend_port)\b" || echo "后端端口 $(backend_port) : 空闲"
    else
      echo '（没有 ss 命令，跳过端口检查）'
    fi
    echo
    echo '== 产物与数据 =='
    for p in server/target/qxfly-image-host.jar web/dist/index.html uploads thumbs dist; do
      if [ -e "$ROOT/$p" ]; then
        echo "$p : $(dir_size_mb "$ROOT/$p") MB"
      else
        echo "$p : 不存在"
      fi
    done
    echo
    echo '== 磁盘 =='
    df -h "$ROOT" 2>/dev/null | tail -n 2
    echo
    echo '== 配置 =='
    [ -f "$ENV_FILE" ] && sed 's/^/  /' "$ENV_FILE" || echo '  （没有 config/local.env）'
  } > "$out" 2>&1
  ok "诊断报告已生成：$out"
  echo
  head -n 24 "$out" | sed 's/^/    /'
}

clean() {
  banner '清理（先算体积，再让你确认）'
  warn '绝不触碰：uploads/（图片实体）、config/local.env、数据库'
  local total=0
  echo
  for item in "thumbs:缩略图缓存" "dist:发行产物" "server/target:后端构建" "web/dist:前端构建"; do
    local path="${item%%:*}" label="${item##*:}"
    if [ -e "$ROOT/$path" ]; then
      local mb; mb="$(dir_size_mb "$ROOT/$path")"
      total=$((total + mb))
      printf '    %-22s %8s MB   %s\n' "$label" "$mb" "$ROOT/$path"
    else
      printf '    %-22s %8s      （不存在）\n' "$label" ''
    fi
  done
  echo
  info "可释放约 ${total} MB"
  printf '    确认清理请输入 yes（其他任何输入取消）: '
  read -r answer
  [ "$answer" = 'yes' ] || { warn '已取消'; return; }
  for path in thumbs dist server/target web/dist; do
    [ -e "$ROOT/$path" ] && rm -rf "$ROOT/$path" && ok "已清理：$path"
  done
  ok '清理完成（下次启动/打包会重新生成）'
}

check_scripts() {
  banner '脚本自检（编码）'
  local problems=0
  for f in "$ROOT"/*.cmd "$ROOT"/scripts/*.cmd; do
    [ -f "$f" ] || continue
    if [ "$(LC_ALL=C tr -d '\000-\177' < "$f" | wc -c)" -ne 0 ]; then
      fail "$(basename "$f")：含非 ASCII 字节（.cmd 必须纯 ASCII）"
      problems=$((problems + 1))
    fi
  done
  for f in "$ROOT"/scripts/*.ps1; do
    [ -f "$f" ] || continue
    local bom; bom="$(head -c 3 "$f" | od -An -tx1 | tr -d ' \n')"
    if [ "$bom" != 'efbbbf' ]; then
      fail "$(basename "$f")：缺少 UTF-8 BOM（PowerShell 5.1 会按 ANSI 读中文）"
      problems=$((problems + 1))
    fi
  done
  if [ "$problems" -eq 0 ]; then
    ok '.cmd 均纯 ASCII、.ps1 均有 UTF-8 BOM'
    return 0
  fi
  warn "发现 $problems 个问题"
  return 1
}

deploy_hint() {
  banner '部署模板'
  local dep="$ROOT/deploy"
  [ -d "$dep" ] || dep="$ROOT/dist/deploy"
  if [ -d "$dep" ]; then
    ok "模板目录：$dep"
    ls -1 "$dep" | sed 's/^/    /'
    echo
    info 'systemd：sudo cp deploy/qxfly-image-host.service /etc/systemd/system/ && sudo systemctl daemon-reload'
    info 'nginx  ：sudo cp deploy/nginx.conf.example /etc/nginx/conf.d/qxfly.conf → 改 server_name → nginx -t && systemctl reload nginx'
    info '细节见包根 DEPLOY-README.txt（服务端包内）或 docs/DEPLOY-ubuntu.md（仓库内）'
  else
    warn '没有 deploy/ 目录：请在 Windows 上执行 toolbox.cmd deploy 生成后再随包分发'
  fi
}

menu() {
  while true; do
    banner 'qxfly-image-host 工具箱（Linux）'
    printf '   1) 启动（生产模式：仅后端，界面由 jar 提供）\n'
    printf '   2) 启动（开发模式：后端 + Vite 前端）\n'
    printf '   3) 查看运行状态\n'
    printf '   4) 停止\n'
    printf '   5) 重启\n'
    printf '   6) 查看当前配置与访问地址\n'
    printf '   7) 查看日志\n'
    printf '   8) 一键诊断\n'
    printf '   9) 清理缓存与产物\n'
    printf '  10) 脚本自检\n'
    printf '  11) 部署模板（nginx / systemd）\n'
    printf '  12) 数据库管家\n'
    printf '   0) 退出\n\n'
    printf '    请选择: '
    read -r choice || return 0
    echo
    case "$choice" in
      1) "$ROOT/start.sh" -Mode prod -Target backend ;;
      2) "$ROOT/start.sh" ;;
      3) "$ROOT/start.sh" -Action status ;;
      4) "$ROOT/start.sh" -Action stop ;;
      5) "$ROOT/start.sh" -Action restart ;;
      6) show_config ;;
      7) show_logs ;;
      8) diag ;;
      9) clean ;;
      10) check_scripts ;;
      11) deploy_hint ;;
      12) printf '    子命令（status/install/start/stop/init/console，回车=status）: '; read -r sub; "$ROOT/mysql.sh" "${sub:-status}" ;;
      0) echo '  再见'; return 0 ;;
      *) warn '请输入 0-12 之间的编号' ;;
    esac
    echo
    printf '    回车返回菜单'; read -r _ || return 0
  done
}

action="${1:-}"
shift || true
case "$action" in
  '') menu ;;
  status|start|stop|restart) "$ROOT/start.sh" -Action "$action" "$@" ;;
  db) if [ $# -eq 0 ]; then "$ROOT/mysql.sh" status; else "$ROOT/mysql.sh" "$@"; fi ;;
  config) show_config ;;
  logs) show_logs ;;
  diag) diag ;;
  clean) clean ;;
  check) check_scripts || exit 1 ;;
  deploy) deploy_hint ;;
  help|-h|--help) menu ;;
  *) fail "未知子命令：$action"; info '可用：status start stop restart db config logs diag clean check deploy help' ;;
esac
