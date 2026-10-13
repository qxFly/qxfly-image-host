# PicHost · 多云端图床管理

一套「一次上传，多端可用」的图床管理系统。同一份 Vue 3 前端既可作为**浏览器端**部署，也可打包成 **Tauri 2 桌面客户端**；后端为 Spring Boot 3 + MySQL，通过统一的存储 SPI 屏蔽 8 种存储后端的差异。

除数据库外，**运行所需环境全部随包自带**（```runtime/``` 目录下的便携 JDK 与 Node），目标机器不需要预装 Java、Node，也不需要联网。

---

## 一、一键包

```powershell
.package.cmd              # 在开发机上构建，产出 dist 下的两个包
.package.cmd -Zip         # 额外生成 zip，便于传输
```

产物：

| 目录 | 自带内容 | 目标机怎么用 |
| --- | --- | --- |
| ```distPicHost-win-x64``` | ```runtimewin-x64{jdk,node}```（已解压）+ 生产版 jar | 拷过去，双击 ```start.cmd``` |
| ```distPicHost-linux-x64``` | ```runtime_cache``` 内的 Linux JDK/Node 压缩包 + 生产版 jar | 拷过去，```./start.sh```（首次自动解压运行时） |

生产版 jar 已经把前端界面打进 ```static/```，所以**一个进程同时提供接口和页面**：

```powershell
.start.ps1 -Mode prod      # Windows：单进程，访问 http://127.0.0.1:8080
./start.sh -Mode prod       # Ubuntu ：单进程，访问 http://127.0.0.1:8080
```

> Linux 的运行时压缩包要由 Ubuntu 自己解压：tar 里的符号链接和权限位在 NTFS 上无法还原。
> 所以 Windows 包内是「解压好的运行时」，Linux 包内是「压缩包 + 首次运行自动解压」。

---

## 二、数据库：注册成服务 + cmd 可控 + 开机自启

MySQL 默认安装在 ```C:Program FilesMySQLMySQL Server 8.0```。项目提供的 ```mysql-service.cmd``` 会：

- 自动从已有服务 / 常见目录探测 MySQL 安装位置
- **注册 Windows 服务**（已存在则跳过），设置 ```start= auto``` 开机自启，并配置异常自动重启
- 把 ```bin``` 目录写进 PATH，**让 cmd 里能直接敲 mysql**
- 初始化业务库与专用账号，把连接信息写进 ```configlocal.env```（start 脚本会自动读取）

```cmd
mysql-service.cmd status                          :: 查看服务状态 / 端口 / PATH / 连接测试
mysql-service.cmd install                         :: 注册服务 + 开机自启（需要时会请求 UAC 提权）
mysql-service.cmd start | stop | restart
mysql-service.cmd autostart on | off
mysql-service.cmd path add | machine | remove     :: add 写用户 PATH，machine 写系统 PATH
mysql-service.cmd init -User root -Password 密码   :: 建库建账号并写 configlocal.env
mysql-service.cmd console                         :: 打开 mysql 命令行
```:: 项目根目录里还有一个 mysql 客户端入口，无需改 PATH 也能直接用：
mysql --version
mysql -uroot -p -e "show databases;"
```

同一套能力在 Ubuntu 上是 ```./mysql.sh```：

```bash
sudo ./mysql.sh install            # apt 安装 + systemctl enable --now
./mysql.sh status
./mysql.sh autostart on
./mysql.sh init -u root -p 密码
```

### 连接信息放在哪

```mysql-service.cmd init``` 会创建业务库 ```pichost``` 与独立账号（随机口令），并写入：

```text
config/local.env
  MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USER / MYSQL_PASSWORD
```

```start.ps1``` / ```start.sh``` 启动时会自动加载它。该文件已被 ```.gitignore``` 忽略。
没有它时会退回到 ```application.yml``` 里的默认口令（root / pichost123），连不上还会自动降级到 H2 内存库，保证一键可用。

---

## 三、自带运行环境

```
runtime/
├── win-x64/
│   ├── jdk/          Temurin JDK 17（解压即用）
│   └── node/         Node.js 20 LTS
├── linux-x64/        Ubuntu 首次运行 start.sh 时自动解压生成
└── _cache/           下载的原始压缩包（Linux 版在此交付）
``` 

获取方式（开发机上执行一次）：

```powershell
.scriptssetup-runtime.ps1              # 拉取 win + linux 两套
.scriptssetup-runtime.ps1 -Platform win
.scriptssetup-runtime.ps1 -Force -KeepArchive
```

启动脚本会优先使用 ```runtime```，找不到才回退系统 PATH。可用 ```-Offline``` 禁止联网下载。

---

## 四、技术栈

| 层次 | 选型 |
| --- | --- |
| 桌面客户端 | Tauri 2（Rust 1.77+） |
| 浏览器端 | Vue 3 + Vite 6 + TypeScript + Element Plus + Pinia + Vue Router + Axios |
| 后端 | Spring Boot 3.5 + MyBatis-Plus 3.5 + Spring Security(JWT) |
| 数据库 | MySQL 8（也可用内置 H2 profile 体验） |
| 自带运行时 | Temurin JDK 17 + Node.js 20 LTS |

## 五、已支持的存储后端

| 类型 | 枚举值 | 说明 |
| --- | --- | --- |
| GitHub | ```GITHUB``` | 仓库文件（Contents API）或 Release 附件，可接 jsDelivr 加速 |
| Cloudflare R2 | ```CLOUDFLARE_R2``` | S3 协议，需绑定自定义域或 r2.dev |
| 阿里云 OSS | ```ALIYUN_OSS``` | 支持自定义域名 |
| 腾讯云 COS | ```TENCENT_COS``` | 存储桶名需带 APPID |
| 七牛云 Kodo | ```QINIU_KODO``` | 需绑定访问域名 |
| SM.MS | ```SMMS``` | 免费图床，匿名可传，配 Token 可删 |
| ImgBB | ```IMGBB``` | 免费图床，Base64 上传，官方 API 不支持删除 |
| S3 兼容 | ```S3_COMPATIBLE``` | MinIO / Ceph / B2 / 自建网关 |

新增一种后端＝加一个枚举项 + 一个 `\@Component` 实现类，前端表单自动生成。

### 从仓库/桶导入已有图片

仓库或存储桶里**原本就存在**的图片（别处传的、或接入平台之前传的），可以在「图库 → 从存储导入」里扫出来并批量登记进平台：

- **全量拉取，不受条数限制**：GitHub 先用 `git/trees?recursive=1` 一次拿整棵树；仓库太大被截断（返回 `truncated=true`）时，会自动退化成「逐级遍历子树」继续拉，直到取完。S3 兼容存储用 `ListObjectsV2` 的 continuation token 翻页续拉。`IMPORT_MAX_FILES`（默认 20000）只作为内存保护上限
- 扫描结果按页返回（每页 200 条），界面可选「导入选中」或「全部导入」；「全部导入」由服务端自己遍历，不受分页影响
- 导入只登记元数据（对象键、大小、访问地址），**不会重新下载或上传文件**；导入的记录没有 sha256 与宽高，因此不参与秒传
- 已导入的文件会标记出来并自动跳过，重复导入不会产生重复记录
- SM.MS / ImgBB 没有列举接口，界面会直接禁用并提示
- 其余类型（阿里云 OSS / 腾讯云 COS / 七牛云）要支持导入，只需实现 `StorageService.list()` 一个方法

### 上传路径与目录分片

上传的对象键形如：

```text
{pathPrefix}/{yyyy}/{MM}/{dd}/{NNN}/{uuid}.{ext}
```

`NNN` 是分片目录，从 `001` 开始编号。同一个存储后端当天每写满 `UPLOAD_SHARD_SIZE`（默认 500）个文件，就自动换到下一个分片目录，
这样单个目录不会堆积成千上万个文件（GitHub 网页端和 Contents API 在目录超过 1000 个文件时都会很难受）。

把 `UPLOAD_SHARD_SIZE` 设为 0 可关闭分片，回到 `{pathPrefix}/{yyyy}/{MM}/{dd}/{uuid}.{ext}` 的老结构。

> **分片规则**：只在「最后一个分片」上继续填，填满 `UPLOAD_SHARD_SIZE` 才顺延到下一个（001 → 002 → …），
> 所以正常情况下任何分片都不会超过这个上限。分配是原子的（进程内槽位 + 加锁），并发上传不会撞到同一个位置；
> 槽位首次使用时从数据库恢复，重启后不会覆盖已有分片。自定义子目录（例如「保留原目录结构」迁移过来的 `holiday/`）不参与分片计数。
>
> 注意：**从存储导入**和**保留了原目录结构的迁移**会原样保留远端的对象键，不经过分片逻辑，
> 所以它们可能往同一个目录里放超过 500 个文件。要把已有目录重新分片，对它做一次「不保留目录结构」的迁移即可。

### 仓库目录整理（临时工具）

「个人中心 → 临时工具 → 仓库目录整理」。把 GitHub 仓库某个目录下的图片文件按系统存储规则搬到另一个目录，
例如把 `api/` 下平铺的图片收进 `api/images/`。

- **只支持 GitHub**：同仓库移动不需要搬运内容
- **不重新下载上传**：GitHub 的 Git Data API 允许新 tree 直接引用**原有 blob 的 SHA**，
  所以整个过程只要 6 次左右的 API 调用、**一个提交**就能搬完任意数量的文件
  （对比通用批量迁移：4107 个文件要 1.2 万次调用、约 2.5 小时，还会留下 4107 个提交）
- **默认只换目录、不改名**：`api/a.png` → `api/images/a.png`
- 可选「按 日期/分片 分层」：变成 `api/images/2026/10/13/001/a.png`；
  也可以关掉「保留原文件名」改用 uuid（就是普通上传的命名方式）
- **重名不覆盖**：保留原文件名时，如果目标位置已经有同名文件，那个文件会被**跳过并单独列出来**，
  预览和执行结果里都会显示数量与具体路径 —— 不会静默覆盖掉已有文件
- 默认只处理**直接放在源目录下**的文件，子文件夹里的不动；勾选「包含子目录」才递归
- 非图片文件按扩展名自动跳过
- **目录写法很宽容**：`api`、`/api/`、`main/api`、`owner/repo/main/api`、
  `https://github.com/owner/repo/tree/main/api` 都会被解析成同一个 `api/`（分支名会被自动去掉）
- **执行前建议先点「自检」**：逐项验证 Token、写权限、分支、最近提交、文件树、配额、以及本次能匹配到多少个文件，
  全只读。排查"执行了但没动静"就看这里
- **没有可移动文件时执行会直接报错**，不会静默成功；已有的同名文件只会被跳过并列出来
- **大批量建议分批搬**：请求里带 `maxFiles`（例如 500）就只搬这么多，搬完立刻提交。
  搬走的文件不再匹配源目录，所以反复执行会自然往下推进，不需要记录进度；中途失败也只损失这一批，
  前面的都已提交。实测 4979 个文件分 10 批、约 17 分钟搬完
- **GitHub 的 tree 接口对大批量不友好**：实测一次提交 1000 个文件（2000 条 tree 记录）会返回
  `502 Server Error` 或 `422 "your request timed out"`，甚至出现**返回 200 但响应体里没有 sha**。
  工具内置了重试，并且会校验拿到的 sha 是不是 40 位十六进制（只看 HTTP 状态码不够）
- 点「预览」可以先看清楚会移动多少个、分片怎么分布、路径长什么样（预览不会占用分片号）
- 执行后会同步更新图库记录的 `objectKey` / `url`；仓库历史里多一个提交，可以用 `git revert` 回退

实测（保留原名 + 不分层）：桩里 `api/a.png`、`api/b.jpg`、`api/c.gif`、`api/readme.txt`、`api/sub/x.png`，
目标目录里预先放了一个同名的 `api/images/a.png`：

- 命中 2 个（`readme.txt` 不是图片、`sub/` 是子目录，都不算）
- `a.png` 因为 `api/images/a.png` 已存在被跳过并列出，**没有覆盖**
- `b.jpg` → `api/images/b.jpg`、`c.gif` → `api/images/c.gif`，文件名原样保留
- tree 只有 4 条记录（2 个新增引用原 blob + 2 个删除），1 个提交

勾上「按 日期/分片 分层」后路径变成 `api/images/2026/10/13/001/a.png`，因为路径不同就不再有重名问题。

### 仓库重复文件清理（临时工具）

清理仓库里同名的重复图片：同名（含 `-1` / `-2` 这类数字后缀）只保留一份，其余删掉。

- **同名不等于同内容，所以不能只看文件名**：判断前会比对每个文件的 blob SHA
  - 内容相同 → 真重复，安全删除
  - 内容不同 → 同名但其实是两张不同的图，**默认不删**，只在结果里列出来
- **只认 `-1` ~ `-99`（1~2 位数字）后缀**，`(1)`、`(2)` 这类名字完全不动；
  `foo-2024.png` 这种年份后缀也不会被误判
- 后缀会**反复剥离**：`foo-1-1.png` 归到 `foo.png` 那一组
- 带 `-N` 后缀但**找不到同名基名**的（可能是唯一的图）默认不删，单独列出
- 每组的保留项**优先选图库里有记录的那一份**，删掉的记录会把库里的行改写过去或清掉，
  所以清理完图库不会出现指向已删文件的坏记录
- 预览（只读）与执行分开，先用预览看清楚再执行

> 实测：6255 个图片里有 872 组重名、全部内容一致，删掉 1256 个；随后发现 `-1-1` 叠加后缀漏网，
> 修正后补删 19 个。两次共清理 1275 个重复文件，仓库 9 个提交，图库记录同步无残留。

### 图库搜索

搜索框前可以切换 **模糊** / **精准** / **目录** 三种模式：

| 模式 | 匹配方式 | 典型用法 |
| --- | --- | --- |
| **模糊**（默认） | 文件名或对象键**包含**关键字 | 记得一部分，找找看 |
| **精准** | 文件名或对象键与关键字**完全相等** | 粘贴一个完整对象键定位某一张图 |
| **目录** | 对象键或文件名**以关键字开头** | 粘贴一个目录路径，列出它下面的所有图 |

- 关键字里的**前导斜杠会被自动忽略**（对象键是相对路径，不以 `/` 开头），
  所以 `/api/images/2026/10/12/002` 和 `api/images/2026/10/12/002` 效果一样
- 「目录」模式填短一点也行：`api/images/2026/10/12/00` 会把 `001` 和 `002` 一起列出来
- 搜不到结果时，空状态会提示当前模式为什么没匹配上（比如用「精准」搜目录）
- 搜索可以和目录筛选、存储后端筛选叠加使用

### 目录树

图库左侧是按 `object_key` 聚合出的目录树，点节点即可只看该目录（**含子目录**），每个节点显示文件数；
图片卡片上也会直接标出它所在的目录。

- `GET /api/images/directories` 返回嵌套结构 + 每层文件数，父目录的数字含所有子目录
- 直接放在根目录（对象键里没有斜杠）的散图归到「（根目录）」节点
- 目录不单独存字段，每次按对象键实时聚合，所以上传 / 导入 / 迁移后立刻就对

### 图片缓存

图库缩略图会缓存到浏览器本地（IndexedDB），**再次进入图库不会重新下载**：

1. 缩略图进入视口才取图（提前 200px 预取），不会一次性下载整页
2. 命中缓存且距上次校验不足 30 分钟 → 直接用缓存，**零网络请求**
3. 超过 30 分钟 → 带 `If-None-Match` / `If-Modified-Since` 做条件请求
   - 服务端回 `304` → 内容没变，复用缓存（省掉整个响应体）
   - 回 `200` 但 `ETag` 与缓存一致 → 同样复用，连响应体都不读
   - `ETag` 变了 → 替换缓存
4. 取不到内容（多为对方 CDN 未开 CORS）→ 自动回退成直接用原图地址，行为与没做缓存时一致；
   同一台主机失败过一次就记住，不会每张图都白发一次请求
5. 同一地址的并发请求会合并成一次

缓存最多 3000 张，超出后淘汰最久未校验的。在「个人中心 → 本地图片缓存」可查看占用并一键清空。

### 图库分页

图库每页条数可选 **30 / 50 / 100 / 200**，也可以选「自定义」直接填整数（1 - 500，超过按 500 处理）。
选择会记在浏览器里，下次打开还是这个值。

### 批量迁移

图库里已经保存的图片可以批量搬家 —— 既可以只换目录前缀（同一存储内整理目录），也可以整个搬到另一个存储后端。

- 入口：图库页勾选图片 → **批量迁移**；不勾选则迁移「当前筛选条件下的全部图片」（需要显式确认）
- **没有数量上限**：迁移是后台任务，提交后立刻返回，几千张也不会把 HTTP 请求拖超时
- **后台任务面板**：工具栏的「迁移任务」列出全部任务（来源 → 目标、状态、进度条、成功/失败/跳过、耗时、当前文件），
  进行中的每 2 秒自动刷新，可以随时取消；任务记录存在内存里，服务重启后列表清空（已迁移的图片不受影响）
- **同仓库换目录自动走「引用式搬移」**：源和目标指向同一个 GitHub 仓库时，不下载也不上传，
  直接让新 tree 引用文件原有的 blob，顺带删掉旧路径 —— 实测 5 个文件只要 3 次 API 调用（tree + commit + 移动引用），
  而老路要 15 次（5 下载 + 5 上传 + 5 删除），而且不会产生重复文件。跨仓库 / 跨云时仍走原有的下载上传路径
- **文件级并发**：每张图要「下载 + 上传」两次网络往返，串行会慢得离谱。并发数由 `MIGRATE_CONCURRENCY` 控制（默认 6）
- **限流重试**：撞上 403 / 429 / 5xx / 超时会退避重试（`MIGRATE_RETRY_TIMES`，默认 2 次），而不是直接判失败
- **命名方式是两个独立开关**：「保留原目录结构」和「保留原文件名」可以任意组合

  | 保留目录 | 保留文件名 | 目标对象键形如 |
  | --- | --- | --- |
  | 否 | 否 | `目标前缀/日期/分片/随机名.png` |
  | 是 | 否 | `目标前缀/源目录结构/随机名.png` |
  | 否 | 是 | `目标前缀/日期/分片/原文件名.png` |
  | 是 | 是 | `目标前缀/源目录结构/原文件名.png` |

  勾选「保留原文件名」时如果重名，会自动追加 `-1`、`-2` 序号，不会覆盖已有文件。
  迁移弹窗里会实时显示目标对象键的样子，不用靠猜
- 可选「迁移成功后删除源文件」（仅跨存储时删；同存储同路径会被跳过，不会误删）
- 支持取消；单张失败只记入错误列表，不影响其余图片，结束时给出 `SUCCESS / PARTIAL / FAILED`
- **失败原因按原因归并**：同一种原因的合并成一条（URL 等易变部分会归一化成 `<地址>`），
  给出完整失败张数 + 最多 10 个示例文件名，按失败数量从多到少排列；不同原因都会列出来，不再有条数上限

本地桩实测（1200 张，S3 桶 → S3 桶，含下载 + 上传 + 改库）：

| 并发数 | 耗时 | 单张 |
| --- | --- | --- |
| 1（串行） | 12.8 秒 | 10.7 ms |
| 8 | **3.7 秒** | **3.0 ms** |

并发 8 时约 **3.5 倍**提速。没有跑到 8 倍是因为每张图还要写一次数据库、且对象键分配需要串行 —— 这两处是剩下的瓶颈。
真实 GitHub 上瓶颈会变成网络往返，并发收益更明显；但要注意 5000 请求/小时的配额。

> 换成真实的 GitHub 时要注意 API 配额：认证用户 5000 请求/小时，而每张图片要「下载 + 上传」两次调用，
> 也就是大约 2500 张/小时。超了会开始失败（任务不会中断，失败的会列在错误里），可以过一小时再对失败的重新跑一次。

## 六、目录结构

```text
PicHost/
├── start.ps1 / start.cmd / start.sh     一键启动（win / linux）
├── mysql-service.cmd / mysql.cmd        MySQL 服务管理 / 客户端入口（Windows）
├── mysql.sh                             MySQL 服务管理（Ubuntu）
├── package.cmd / scripts/package.ps1    一键打包
├── runtime/                             便携运行时（除数据库外的全部环境）
├── scripts/setup-runtime.ps1            下载便携 JDK / Node
├── scripts/mysql-service.ps1            MySQL 服务管理实现
├── config/local.env                     数据库连接（init 生成，已忽略）
├── server/                              Spring Boot 3 后端
│   ├── mvnw / mvnw.cmd                  自带 Maven 包装器
│   └── src/main/java/com/pichost/
│       ├── common/ config/ controller/ dto/ entity/ mapper/ security/ service/
│       └── storage/                     ★ 存储抽象
│           ├── StorageType.java         8 种类型 + 表单字段元数据
│           ├── StorageService.java      SPI 接口
│           ├── impl/                    8 个实现
│           └── support/                 HTTP / S3 / JSON / 对象键工具
└── web/                                 Vue 3 前端（浏览器端 + 桌面端共用）
    └── src-tauri/                       Tauri 2 桌面外壳
``` 

## 七、源码方式启动（开发用）

```powershell
.start.ps1                      # 后端 + Vite 前端（http://localhost:5173）
.start.ps1 -Action status
.start.ps1 -Action stop
.start.ps1 -Database h2         # 强制内存库
.start.ps1 -Target desktop      # Tauri 桌面端
.start.ps1 -Rebuild             # 强制重新编译后端
```bash
./start.sh                       # Ubuntu 等价用法
```powershell
docker compose up -d             # 需要 MySQL / MinIO 时（可选）
```

默认管理员 **admin / admin123**，登录后请立即修改。

## 八、配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| ```SERVER_PORT``` | 8080 | 服务端口 |
| ```SPRING_PROFILES_ACTIVE``` | mysql | 设为 ```h2``` 使用内存库 |
| ```MYSQL_HOST``` / ```MYSQL_PORT``` / ```MYSQL_DATABASE``` | 127.0.0.1 / 3306 / pichost | 数据库连接 |
| ```MYSQL_USER``` / ```MYSQL_PASSWORD``` | root / pichost123 | 数据库账号（会被 config/local.env 覆盖） |
| ```JWT_SECRET``` | 开发用默认值 | **生产必须覆盖**，长度 ≥ 32 字节 |
| ```CRYPTO_KEY``` | 开发用默认值 | **生产必须覆盖**，存储密钥的 AES 口令 |
| ```UPLOAD_MAX_SIZE``` | 31457280 | 单张图片字节上限（30MB） |
| `UPLOAD_DEDUPE` | true | 同用户相同内容秒传 |
| `UPLOAD_SHARD_SIZE` | 500 | 单个目录文件数阈值，超过就换下一个 001/002 分片目录（0 = 关闭） |
| `IMPORT_MAX_FILES` | 20000 | 从存储导入时最多扫描多少个远端文件 |
| `MIGRATE_CONCURRENCY` | 6 | 迁移时的文件级并发数（提速主要靠它） |
| `MIGRATE_RETRY_TIMES` | 2 | 迁移遇到限流 / 5xx / 超时的重试次数 |
| ```ADMIN_USERNAME``` / ```ADMIN_PASSWORD``` | admin / admin123 | 初始管理员 |
| ```CORS_ORIGINS``` | * | 允许的跨域来源 |

> ```CRYPTO_KEY``` 一旦更换，已保存的存储密钥将无法解密，需要重新录入。

## 九、接口概览

统一响应 ```{ code, message, data, timestamp }```，```code = 200``` 为成功。
除登录/注册外都需要 ```Authorization: Bearer <token>```。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/auth/login · /register | 登录 / 注册 |
| GET | /api/auth/me | 当前用户 |
| GET | /api/storage-configs/types | 存储类型 + 表单字段定义 |
| GET/POST/PUT/DELETE | /api/storage-configs | 存储配置增删改查 |
| POST | /api/storage-configs/{id}/test | 测试连接 |
| POST | /api/images/upload · /batch · /base64 | 上传（三种方式） |
| GET | /api/images | 分页查询（支持 directory 目录筛选） |
| GET | /api/images/directories | 目录树 |
| POST | /api/images/repo-move/preview | 【临时】仓库目录整理预览 |
| POST | /api/images/repo-move/execute | 【临时】仓库目录整理执行 |
| DELETE | /api/images/{id} | 删除（含云端对象） |
| GET | /api/images/stats | 统计汇总 |
| GET | /api/images/import/preview | 扫描存储后端已有文件（标记是否已导入） |
| POST | /api/images/import | 把选中的远端文件导入图库 |
| POST | /api/images/migrate | 启动批量迁移（后台任务，返回 taskId） |
| GET | /api/images/migrate | 后台迁移任务列表 |
| GET | /api/images/migrate/{taskId} | 查询迁移进度 |
| POST | /api/images/migrate/{taskId}/cancel | 取消迁移 |
| GET | /api/system/info | 系统信息与上传限制 |
| GET | /actuator/health | 健康检查（免鉴权） |

详细字段见 [docs/API.md](docs/API.md)，各后端参数获取见 [docs/STORAGE.md](docs/STORAGE.md)。

## 十、数据库表

| 表 | 用途 |
| --- | --- |
| ```sys_user``` | 用户，密码 BCrypt，角色 ADMIN/USER |
| ```storage_config``` | 存储后端配置，密钥字段 AES-GCM 加密 |
| ```image_file``` | 图片元数据，含 sha256（秒传）、宽高、对象键、访问地址 |

建表脚本：```server/src/main/resources/db/schema-mysql.sql```（启动时幂等执行）。

## 十一、安全设计

- 密码 BCrypt，登录态为无状态 JWT。
- 存储密钥 AES-256-GCM 加密入库，接口回显统一 ```******```，编辑时保留占位符即不修改。
- 存储配置按 ```user_id``` 隔离，越权返回 403。
- 上传做扩展名白名单 + 大小限制校验。

## 十二、进度与规划

已完成：

- [x] 8 种存储后端全部实现 + 统一 SPI
- [x] JWT 鉴权、多用户隔离、密钥加密、秒传、分页图库、统计
- [x] Vue 3 浏览器端全部页面
- [x] Tauri 2 桌面外壳
- [x] 一键启动 / 一键打包（Windows + Ubuntu 双平台）
- [x] 除数据库外的环境随包自带（便携 JDK + Node）
- [x] MySQL 注册服务、开机自启、cmd 可控、自动建库
- [x] 从存储后端导入已有图片（GitHub / S3 兼容，全量拉取）
- [x] 图库批量迁移（换目录 / 换存储，后台任务无数量上限）
- [x] 上传按目录自动分片（单目录文件数可控）

建议下一步：

- [ ] 对象存储直传（前端拿临时签名直传，绕过后端带宽）
- [ ] 图片压缩 / 缩略图 / WebP 转换
- [ ] 相册标签与分享链接
- [ ] 开放 API Token，供 Typora / PicGo 调用
- [ ] 批量删除与跨后端迁移
