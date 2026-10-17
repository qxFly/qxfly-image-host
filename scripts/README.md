# scripts 目录说明

所有实现脚本都集中在这里；项目根目录只保留「入口」，双击即可用。

## 根目录入口

| 入口 | 用途 |
| --- | --- |
| `start.cmd` | **一键启动**（独立入口，不进菜单）。转发到 `scripts\start-services.ps1` |
| `toolbox.cmd` | **工具箱**：不带参数进菜单；带参数走子命令（见下） |
| `mysql.cmd` | 项目内 `mysql` 客户端（无需把 MySQL bin 加进 PATH） |
| `package-server.cmd` | **一键打包服务端包**（扁平目录 + 冒烟测试，上传服务器即用）。等价于 `toolbox.cmd package-server` |
| `package.cmd` | 打包完整包（Windows + Linux 两个目录；等价于 `toolbox.cmd package`） |
| `mysql-service.cmd` | 数据库管家（兼容保留，等价于 `toolbox.cmd db`） |
| `start.sh` / `mysql.sh` | Linux 侧对应入口 |
| `toolbox.sh` | Linux 侧工具箱（菜单 + 子命令，与 `toolbox.cmd` 对齐） |

## 脚本清单

| 文件 | 用途 |
| --- | --- |
| `common-library.ps1` | **公共库**：统一输出、进度日志 + trap、编码安全的复制/删除、停占用 jar 的进程、端口工具、脚本自检 |
| `start-services.ps1` | 一键启动实现（环境检查 → 数据库探测 → 构建 → 启动后端/前端） |
| `toolbox-menu.ps1` | 工具箱实现（菜单 + 子命令） |
| `package-release.ps1` | 打包：`-Target all`（Win + Linux 完整包）/ `-Target server`（服务端包，含 Windows + Linux 入口）、`-Runtime none\|linux\|win\|both`（附带哪个运行时）、`-Zip`、`-SkipBuild`、`-Verify`（冒烟测试）、`-Slim`（=`-Runtime none`，约 500 MB → 约 90 MB） |
| `package-desktop.ps1` | 打包 Tauri 桌面客户端 |
| `setup-portable-runtime.ps1` | 下载并解压便携 JDK + Node |
| `manage-mysql-service.ps1` | 数据库管家（注册服务/启停/自启/PATH/初始化库） |
| `generate-app-icons.mjs` | 生成图标 |

## toolbox 子命令（给自动化/CI 用）

```
toolbox.cmd status | start | stop | restart     启动相关（转发给 start-services.ps1）
toolbox.cmd package-server                      一键打服务端包（默认 Linux 部署版 + 冒烟测试）
toolbox.cmd package-server -Runtime win         服务端包 + Windows 测试运行时
toolbox.cmd package-server -Slim                服务端包瘦身版（目标机自备 JDK）
toolbox.cmd package [-Zip]                      打包完整包（Windows + Linux）
toolbox.cmd package -Target server -Verify      只打服务端包并做冒烟测试
toolbox.cmd package -Target server -Runtime win   服务端包带 Windows 便携 JDK（可在 Windows 上直接测试）
toolbox.cmd package -Target server -Runtime linux 服务端包带 Linux 便携归档（部署 Ubuntu，默认）
toolbox.cmd package -Target server -Runtime both  两种运行时都带
toolbox.cmd package -Target server -Slim          不带运行时（目标机自备 JDK）
toolbox.cmd desktop                             打包 Tauri 桌面客户端
toolbox.cmd runtime                             准备便携运行时
toolbox.cmd db status|install|start|stop|init   数据库管家
toolbox.cmd check                               脚本自检；有问题时退出码 1（可直接当门禁用）
toolbox.cmd diag                                一键诊断，产出报告到 logs\diag-*.txt
toolbox.cmd clean                               清理缓存与产物（先算体积再确认）
toolbox.cmd config                              查看生效配置与访问地址
toolbox.cmd logs [名称]                         查看日志
toolbox.cmd deploy                              生成 nginx / systemd 模板到 dist\deploy
```

## 服务端包：按需选择「环境 + 运行时」

`package-server.cmd` 产出**扁平的服务端包**：整个目录上传到服务器即可启动（`./start.sh -Mode prod`）。

**不带参数双击 → 交互向导**（选环境 / 是否带运行时 / 是否冒烟测试）：

```
 1) Linux / Ubuntu  —— 只放 start.sh / toolbox.sh / mysql.sh（上传服务器用）
 2) Windows         —— 只放 start.cmd 等，用于在本机跑起来测试
 3) 两个都要         —— 一个包两边都能跑（体积最大）
```

**带参数 → 非交互**（自动化 / CI）：

| 命令 | 入口 | 运行时 | 体积 |
| --- | --- | --- | --- |
| `package-server.cmd -Env linux -Runtime none` | Linux | 无 | 约 90 MB |
| `package-server.cmd -Env linux` | Linux | Linux 归档 | 约 290 MB |
| `package-server.cmd -Env win -Runtime win` | Windows | Windows JDK | 约 460 MB |
| `package-server.cmd -Env both` | 两套 | 两套 | 约 670 MB |

- `-Env` 决定放哪套入口：`linux` / `win` / `both`（默认 `both`）
- `-Runtime` 不显式指定时**按 `-Env` 自动推导**（`linux`→Linux 归档、`win`→Windows JDK、`both`→两套）
- `-Slim` = `-Runtime none`（旧写法保留）；`-NoVerify` 跳过冒烟测试；`-SkipBuild` 只重新组装
- 工具箱菜单 6~9 也是常用组合：向导 / Linux 极简 / Linux 带运行时 / Windows 测试用

包结构（jar 直接放包根，**没有** `server\target\` 两层嵌套）：

```text
qxfly-image-host-server\
├── qxfly-image-host.jar          应用本体（界面已打进 jar）
├── start.sh / toolbox.sh / mysql.sh        Ubuntu 入口
├── start.cmd / toolbox.cmd / mysql.cmd / mysql-service.cmd / scripts\   Windows 一键入口（双击即测）
├── BUILD-INFO.txt / DEPLOY-README.txt
├── config\local.env.example      复制成 local.env 再改
├── deploy\nginx.conf.example + qxfly-image-host.service
├── runtime\                      _cache（Linux 归档）+ win-x64（Windows 便携 JDK，取决于 -Runtime）
└── uploads\ thumbs\ logs\        运行后数据（都在包根，备份 uploads\ 即可）
```

Windows 上快速测试（H2 内存库 + 临时端口，不碰本机 MySQL）：

```bat
cd dist\qxfly-image-host-server
start.cmd -Mode prod -Database h2 -BackendPort 18090 -Offline
rem 另开窗口停止：  start.cmd -Action stop -BackendPort 18090
```

实测结果：启动脚本打印 `检测到服务端包布局（只有 jar，没有源码）：自动按 -Mode prod -Target backend 启动`，
数秒内（实测 3 秒）`/actuator/health` 返回 `{"status":"UP"}`，首页 HTTP 200（标题 `qxfly-image-host 图床管理`），
且 `uploads\ thumbs\ logs\` 都落在包根（没有 `server\` 子目录）。

## 两条编码红线（改脚本前务必确认）

1. **`.cmd` 一律纯 ASCII**：cmd.exe 按当前代码页解析，`.cmd` 里的中文会被拆坏并执行出垃圾命令（曾出现 `'的包'`、`'ckage.cmd'` 报错）。中文输出放在 `.ps1` 里。
2. **`.ps1` 一律保留 UTF-8 BOM**：没有 BOM 时 Windows PowerShell 5.1 按 ANSI 读中文，会吞掉后续代码，表现为「脚本莫名提前结束、退出码 0」。

随时可以自检：`toolbox.cmd check` / `./toolbox.sh check`（.cmd 纯 ASCII、.ps1 有 BOM、语法能过解析器）。
**发现问题时退出码为 1**，所以 CI 里可以直接当门禁用（`.github/workflows/ci.yml` 的 `scripts` 任务就是这么做的）。

## 入口脚本的调用方式（别改回去）

根目录的 `.cmd` 一律这样调用实现脚本：

```bat
%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\xxx.ps1" %*
set "RC=%ERRORLEVEL%"
rem ...（交互式入口在这里 pause）
exit /b %RC%
```

原因（都踩过）：

- 必须用 **`-File`**：早期用 `-Command ". '脚本'"`（dot-source）或 `-Command "& '脚本'"` 时，
  脚本里的 `exit 1` **不会**设置进程退出码，导致自检/打包失败了却返回 0，脚本化判断失效。
- 必须 **`set "RC=%ERRORLEVEL%"` + `exit /b %RC%`**：`.cmd` 结尾的 `goto :eof` 或 `pause` 会丢掉退出码。
- 历史记录：曾经把打包中途静默退出（退出码 0）误判为 `-File` 的锅，实际原因是「用管道捕获外部程序输出」
  和「`.ps1` 丢了 UTF-8 BOM」。这两个根因已修复，`-File` 现在是正确且必要的调用方式。

## 其他约定

- **不通过管道捕获外部程序的输出**（`cmd | Out-Null`、`robocopy | Out-Null` 等）：在本项目环境里会让脚本中途被终止。改用 `Start-Process` + 输出重定向到文件。
  **打包命令尤其要注意**：`package-server.cmd` / `package.cmd` 的输出不要接管道（例如 `package-server.cmd -SkipBuild | Out-String`），实测会让打包进程在复制运行时的中途被杀掉，留下一个残包。
- **残包识别**：`BUILD-INFO.txt`、`DEPLOY-README.txt`、jar 都是打包**最后一步**才写入的，所以缺了任何一个就说明这次打包中途死了。
  `package-server.cmd` 已内置这个守卫：不完整会打印 `[x] ... is INCOMPLETE` 并以退出码 1 结束，同时提示重新打包。
- **两个启动脚本都兼容扁平服务端包**：`scripts/start-services.ps1`（Windows）与 `start.sh`（Linux）在「只有 jar、没有源码」时会自动按 `-Mode prod -Target backend` 启动，不再报「不像是项目根目录」。
- **`Start-Process -ArgumentList` 不自动加引号**：包含空格的路径（如 `My Projects`）必须显式加引号，否则对方拿到被截断的路径。
- 删大目录用 `rmdir`（等待删除真正落地），避免 `Remove-Item` 留下 delete-pending 状态导致后续复制 `Access denied`。
- 每次打包/启动都会写进度日志（`logs\package-*.log`、`logs\start-*.log`），出问题先看它。
