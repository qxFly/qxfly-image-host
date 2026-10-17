# 部署到 Ubuntu 服务器

> 目标：把服务端跑在 Ubuntu 上，支持「nginx 反向代理」或「服务器 IP + 端口直连」两种访问方式。

## 一、服务端 / 客户端目录划分

### 必须放到服务器

| 内容 | 说明 |
| --- | --- |
| `qxfly-image-host.jar` | 唯一核心产物，**前端界面已打进 jar**（扁平包里在包根；旧布局在 `server/target/`，两种都支持） |
| JDK | 便携 `runtime/linux-x64/jdk`（由 `runtime/_cache/jdk-linux-x64.tar.gz` 解压）**或**系统 `openjdk-17-jre-headless` |

> **运行环境要求只有 JDK 17**：部署包不需要 Maven、不需要 Node、也不需要 `unzip`。
> 启动脚本在「只有 jar、没有源码」的部署包里会直接用现有 jar，不会尝试重新编译。
>
> **端口**写在 `config/local.env`：`BACKEND_PORT=8080`（启动脚本读这个）。`SERVER_PORT` 也认，两者等价
> （脚本最终会把 `SERVER_PORT` 导出给应用，`application.yml` 读的就是它）。改完直接重启即可，命令行 `-BackendPort` 优先级最高。
| `config/local.env` | 端口、数据库、`PUBLIC_BASE_URL` |
| `start.sh` | 前台启动（生产建议改用 systemd） |
| `mysql.sh` + MySQL | 仅当数据库也在本机 |
| `deploy/` | nginx 与 systemd 模板、部署说明 |

运行时自动生成：`uploads/`（**图片实体，必须备份**）、`thumbs/`（缓存，可删）、`logs/`。

### 不需要放到服务器

- `web/`（前端源码；界面已在 jar 里）
- `web/src-tauri/`、`dist/desktop/`（Tauri 桌面客户端，属于客户端）
- `server/src`、`.mvn`、`mvnw`（服务器只跑 jar）
- `scripts/*.ps1`（Windows 打包脚本）、`.tools/`、`.github/`
- `runtime/win-x64`、`runtime/_cache`（解压后可删，重装才需要）
- **Node 不需要**：生产模式（`-Mode prod`）界面由 jar 提供

> 一键打服务端包：`package-server.cmd`（等价 `toolbox.cmd package-server`）。不带参数进向导：选环境 + 是否带运行时；
> 也可直接给参数：`-Env linux|win|both`（放哪套入口）、`-Runtime none|linux|win|both`（是否带便携运行时，默认按 `-Env` 推导）。
> 部署 Ubuntu 用 `-Env linux`（约 290 MB）；目标机已有 JDK 17 就加 `-Runtime none`（约 90 MB）。
>
> **它是扁平布局**：`qxfly-image-host.jar` 直接在包根，`config/ uploads/ thumbs/ logs/ runtime/ deploy/` 也都在包根
> （没有 `server\target\` 两层嵌套）；包根还有 `DEPLOY-README.txt` 说明每个文件干什么。
> 整个目录上传到服务器后：`cp config/local.env.example config/local.env` → 改端口/数据库/PUBLIC_BASE_URL → `./start.sh -Mode prod`。
>
> 包内**同时带 Windows 一键入口**（`start.cmd` / `toolbox.cmd` / `scripts\`）：在 Windows 上直接双击 `start.cmd` 就能测同一个包
> （会自动识别包布局、只跑后端；想用内存库验证：`start.cmd -Mode prod -Database h2 -BackendPort 18090 -NoBrowser`；
> 停止：`start.cmd -Action stop -BackendPort 18090`）。
> `-Env win` 才带 Windows 便携 JDK（约 360 MB，可离线测试）；只想上传就用 `-Env linux`（约 290 MB），
> 目标机自备 JDK 时用 `-Env linux -Runtime none`（约 90 MB）。

## 二、两种访问方式

| 项目 | IP:端口直连 | Nginx 反向代理 |
| --- | --- | --- |
| 监听 | 8080 直接暴露 | 8080 只绑 `127.0.0.1`，对外 80/443 |
| `PUBLIC_BASE_URL` | `http://<服务器IP>:8080` | `https://你的域名`（或 `http://<服务器IP>`） |
| 只监听本机 | — | `SERVER_ADDRESS=127.0.0.1` |

反代必配项（模板见 `deploy/qxfly.conf`，由 `toolbox.cmd deploy` 生成）：

- `proxy_set_header Host $host;` —— 决定生成链接的主机名
- `X-Forwarded-For` / `X-Forwarded-Proto` —— 来源 IP 与协议
- `client_max_body_size` **必须 ≥ 应用的单张上限**（默认 30MB，可在系统设置里改成无上限）
- `proxy_request_buffering off;` + `proxy_read_timeout 600s;` —— 上传是流式的，别让 nginx 先整包落盘
- `/api`、`/thumb`、`/files` 都要走反代（模板里的 `location /` 已覆盖）

## 三、systemd（生产推荐）

```bash
# 目录约定：/opt/qxfly-image-host
sudo mkdir -p /opt/qxfly-image-host && sudo chown $USER /opt/qxfly-image-host
# 把服务端包内容拷进去，然后：
cp config/local.env.example config/local.env   # 改端口/数据库/PUBLIC_BASE_URL
sudo cp deploy/qxfly-image-host.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now qxfly-image-host
journalctl -u qxfly-image-host -f
```

> `WorkingDirectory` 决定 `uploads/`、`thumbs/`、`logs/` 的相对位置，务必设对。

## 四、升级流程

1. 备份数据库与 `uploads/`
2. 替换 `qxfly-image-host.jar`
3. `sudo systemctl restart qxfly-image-host`（数据库结构由启动时的幂等脚本自动迁移）
4. `deploy/` 里的 `BUILD-INFO.txt` 可确认当前跑的是哪个版本（git 提交、构建时间、jar 哈希）

## 五、备份与磁盘

- **两层备份**：数据库（元数据）+ `uploads/`（图片实体）。若改用 S3/OSS 后端，服务器上就只剩元数据
- 回收站会占磁盘（本地后端删除是移到回收站目录，`_trash` 前缀），定期清空回收站可释放空间
- 缩略图缓存有上限（默认 2GB / 30 天，自动淘汰），可用 `THUMB_CACHE_DIR` 指到临时盘

## 六、排查

```bash
./toolbox.sh diag     # 端口/环境/产物/配置一键诊断
./toolbox.sh logs     # 看日志
```

Windows 侧对应 `toolbox.cmd diag` / `toolbox.cmd logs`。
