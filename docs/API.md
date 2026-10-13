# PicHost API 文档

Base URL：`http://127.0.0.1:8080`
所有业务接口都以 `/api` 开头。

## 通用约定

### 响应结构

```json
{
  "code": 200,
  "message": "ok",
  "data": {},
  "timestamp": 1760000000000
}
```

- `code = 200`：成功
- `code = 400`：参数错误 / 业务校验失败（例如存储配置缺必填项）
- `code = 401`：未登录或登录失效
- `code = 403`：无权访问该资源
- `code = 404`：资源不存在
- `code = 413`：上传文件超过大小限制
- `code = 500`：服务端异常（`message` 会带上具体原因，如云厂商返回的错误）

### 鉴权

登录后返回的 `token` 通过请求头携带：

```http
Authorization: Bearer eyJhbGciOiJIUzM4NCJ9...
```

## 认证

### POST /api/auth/login

```json
// 请求
{ "username": "admin", "password": "admin123" }

// 响应 data
{
  "token": "eyJhbGciOiJIUzM4NCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 604800,
  "user": { "id": 1, "username": "admin", "nickname": "超级管理员", "role": "ADMIN", "status": 1 }
}
```

### POST /api/auth/register

```json
{ "username": "alice", "password": "123456", "nickname": "Alice", "email": "a@b.com" }
```

用户名规则：3-32 位字母、数字、下划线或短横线。

### GET /api/auth/me

返回当前登录用户。

### PUT /api/auth/profile

```json
{ "nickname": "新昵称", "email": "new@example.com" }
```

### PUT /api/auth/password

```json
{ "oldPassword": "admin123", "newPassword": "newpass123" }
```

## 存储配置

### GET /api/storage-configs/types

返回所有存储类型及其表单字段定义，前端据此动态渲染配置表单。

```json
[
  {
    "name": "GITHUB",
    "label": "GitHub",
    "shortLabel": "仓库 / Releases",
    "description": "使用 GitHub 仓库 Contents API 或 Release 附件作为图床……",
    "fields": [
      {
        "key": "owner",
        "label": "仓库所有者",
        "type": "text",
        "required": true,
        "secret": false,
        "placeholder": null,
        "defaultValue": null,
        "options": [],
        "help": "GitHub 用户名或组织名，例如 octocat"
      },
      {
        "key": "token",
        "label": "Personal Access Token",
        "type": "password",
        "required": true,
        "secret": true,
        "help": "classic 需要 repo 权限，fine-grained 需要 Contents 读写权限"
      }
    ]
  }
]
```

`type` 取值：`text`、`password`、`number`、`switch`、`select`。
`secret = true` 的字段会加密入库，接口回显为 `******`。

### GET /api/storage-configs

```json
[
  {
    "id": 1,
    "name": "本地 MinIO",
    "type": "S3_COMPATIBLE",
    "typeLabel": "S3 兼容存储",
    "config": {
      "endpoint": "http://127.0.0.1:9000",
      "accessKeyId": "minioadmin",
      "secretAccessKey": "******",
      "bucket": "pichost",
      "region": "us-east-1",
      "publicBaseUrl": "http://127.0.0.1:9000/pichost",
      "pathStyle": "true",
      "pathPrefix": "images"
    },
    "isDefault": true,
    "enabled": true,
    "remark": "本地开发",
    "createdAt": "2026-01-01T10:00:00",
    "updatedAt": "2026-01-01T10:00:00"
  }
]
```

### POST /api/storage-configs

```json
{
  "name": "阿里云 OSS",
  "type": "ALIYUN_OSS",
  "config": {
    "endpoint": "oss-cn-hangzhou.aliyuncs.com",
    "accessKeyId": "LTAI...",
    "accessKeySecret": "xxxx",
    "bucket": "my-images",
    "pathPrefix": "images"
  },
  "isDefault": true,
  "enabled": true,
  "remark": "主账号"
}
```

第一个配置会自动成为默认配置。保存前会做一次必填项校验，缺字段直接返回 400。

### PUT /api/storage-configs/{id}

字段同新增。**敏感字段：**

- 传 `******` 或留空 → 保留数据库中的原值
- 传新明文 → 覆盖并重新加密

### DELETE /api/storage-configs/{id}

删除后如果删掉的是默认配置，会自动把最早的一条设为默认。

### PUT /api/storage-configs/{id}/default

设为默认并自动启用。

### POST /api/storage-configs/{id}/test

用已保存的配置做连通性校验。

```json
// 成功
{ "code": 200, "message": "ok", "data": { "message": "连接成功，凭证有效" } }
// 失败
{ "code": 400, "message": "连接【阿里云 OSS】失败: ...", "data": null }
```

### POST /api/storage-configs/test?existingId=1

测试尚未保存的表单内容，便于「先测连通性再保存」。
带 `existingId` 时，表单里为占位符的敏感字段会复用该配置已保存的密文。

## 图片

### POST /api/images/upload

`multipart/form-data`

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| file | 是 | 图片文件 |
| storageConfigId | 否 | 指定存储后端，缺省用默认配置 |

返回上传后的图片对象：

```json
{
  "id": 1,
  "originalName": "demo.png",
  "objectKey": "images/2026/01/01/fb32e615790743309e2800e6d0afdc0e.png",
  "url": "http://127.0.0.1:9000/pichost/images/2026/01/01/fb32e615790743309e2800e6d0afdc0e.png",
  "thumbUrl": null,
  "mimeType": "image/png",
  "size": 70,
  "width": 1,
  "height": 1,
  "storageType": "S3_COMPATIBLE",
  "storageTypeLabel": "S3 兼容存储",
  "storageConfigId": 1,
  "storageConfigName": "本地 MinIO",
  "createdAt": "2026-01-01T10:00:00",
  "markdown": "![demo.png](http://...)"
}
```

同一用户重复上传**相同内容**时会命中秒传，直接返回已有记录（`UPLOAD_DEDUPE=true` 时生效）。

### POST /api/images/upload/batch

`multipart/form-data`，字段名 `files`（可重复），返回图片对象数组。

### POST /api/images/upload/base64

```json
{
  "content": "data:image/png;base64,iVBORw0KGgo...",
  "fileName": "demo.png",
  "storageConfigId": 1
}
```

`content` 可以是 data URI，也可以是纯 Base64。适合桌面端、编辑器插件等无法直接提交 multipart 的场景。

### GET /api/images

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| page | 1 | 页码 |
| size | 30 | 每页条数，任意整数，最大 500（超过按 500 处理） |
| keyword | - | 按文件名 / 对象键搜索 |
| matchMode | fuzzy | `fuzzy` = 包含即匹配；`exact` = 文件名或对象键完全相等；`prefix` = 对象键或文件名以关键字开头（目录查询） |
| storageConfigId | - | 按存储后端过滤 |
| directory | - | 按目录前缀过滤，**含子目录**；填 `__root__` 表示只看根目录下的散图 |

```json
{
  "total": 4980,
  "page": 1,
  "size": 30,
  "pages": 166,
  "records": [
    {
      "id": 1,
      "objectKey": "images/2026/10/12/001/a.png",
      "directory": "images/2026/10/12/001",
      "depth": 5,
      "url": "...",
      "markdown": "..."
    }
  ]
}
```

`directory` 是对象键去掉文件名后的部分（根目录下为空串），`depth` 是目录层级。

> `keyword` 会先做归一化：去掉首尾空白和**前导斜杠**。对象键是相对路径、不以 `/` 开头，
> 但用户经常整段粘贴带前导斜杠的路径，不处理的话连模糊查询都匹配不到。

### GET /api/images/{id}

返回单张图片详情。

### DELETE /api/images/{id}

先调用对应存储后端删除云端对象，再删除本地记录。
若云端删除失败（例如 ImgBB 官方 API 不支持删除），会记录告警日志但**仍然清理本地记录**。

### GET /api/images/stats

```json
{
  "totalCount": 128,
  "totalSize": 84213760,
  "todayCount": 6,
  "storageConfigCount": 3,
  "typeDistribution": [
    { "name": "S3_COMPATIBLE", "label": "S3 兼容存储", "value": 100 },
    { "name": "GITHUB", "label": "GitHub", "value": 28 }
  ],
  "recent": [ { "id": 128, "url": "..." } ]
}
```

### GET /api/images/directories

目录树：按 `object_key` 实时聚合出目录层级与文件数，用于图库左侧的目录导航。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| storageConfigId | - | 只看某个存储后端的目录 |

```json
{
  "total": 4980,
  "rootFileCount": 1,
  "nodes": [
    {
      "path": "api",
      "name": "api",
      "count": 4513,
      "totalCount": 4980,
      "children": [
        { "path": "api/images", "name": "images", "count": 0, "totalCount": 467, "children": [] }
      ]
    }
  ]
}
```

- `count` 是该目录下直接存放的文件数，`totalCount` 是含所有子目录的总数
- `path` 可直接作为 `GET /api/images` 的 `directory` 参数
- `rootFileCount` 是直接放在根目录（对象键里没有斜杠）的散图数量，对应筛选参数 `__root__`
- 目录不单独存字段，每次按对象键聚合，所以上传 / 导入 / 迁移后立刻生效

## 从存储导入已有文件

用于把仓库/桶里**已经存在**、但平台还没登记的图片批量导入图库。

### GET /api/images/import/preview

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| storageConfigId | 是 | 存储配置 ID |
| prefix | 否 | 只扫描该前缀，留空则用配置里的 pathPrefix |
| page | 否 | 页码，默认 1 |
| size | 否 | 每页条数，默认 200，上限 500 |

远端文件可能有几万个，一次性全回传没有意义，所以返回「总览 + 当前页候选」：

```json
{
  "scanned": 1200,
  "imported": 0,
  "importable": 1200,
  "truncated": false,
  "page": 1,
  "size": 200,
  "totalPages": 6,
  "candidates": [
    {
      "objectKey": "images/2026/01/01/a.png",
      "fileName": "a.png",
      "size": 1234,
      "url": "https://raw.githubusercontent.com/o/r/main/images/2026/01/01/a.png",
      "lastModified": null,
      "imported": false
    }
  ]
}
```

- `scanned`：远端扫描到的图片总数
- `importable` / `imported`：待导入 / 已导入的数量
- `truncated`：为 true 表示达到 `IMPORT_MAX_FILES` 上限没拉全，可调大该环境变量
- `candidates`：只包含当前页

`imported = true` 表示平台里已有该对象记录。

### POST /api/images/import

```json
{
  "storageConfigId": 3,
  "prefix": "images",
  "objectKeys": ["images/2026/01/01/a.png"],
  "limit": 500
}
```

`objectKeys` 留空表示导入扫描到的全部未导入文件。

```json
{ "scanned": 3, "imported": 3, "skipped": 0 }
```

该接口只登记元数据，不会下载或重新上传文件；导入的记录没有 sha256 与宽高，因此不参与秒传。
存储类型不支持列举时返回 code = 400 并说明原因（目前支持 GitHub 与 S3 兼容存储）。

## 批量迁移

把图库里已保存的图片搬到另一个路径前缀或另一个存储后端。

迁移是**后台任务**：接口立刻返回 taskId，之后轮询进度。因此没有数量上限，几万张也不会让请求超时。

### POST /api/images/migrate

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| targetStorageConfigId | 是 | 目标存储配置 |
| targetPrefix | 否 | 目标路径前缀，留空则用目标配置的 pathPrefix |
| preserveDirectory | 否 | true = 保留源文件的目录结构（相对源配置的路径前缀） |
| preserveFileName | 否 | true = 保留源文件名；false = 用 uuid 重新命名（重名自动加 -1、-2） |
| deleteSource | 否 | 迁移成功后删除源文件（仅跨存储时删） |
| imageIds | 否 | 指定要迁移的图片 ID |
| sourceStorageConfigId | 否 | 按来源存储筛选（与 imageIds 二选一） |
| keyword | 否 | 按文件名 / 对象键模糊筛选 |
| migrateAll | 否 | 不传 imageIds 时，必须显式置 true 才允许迁移整个筛选结果 |

```json
// 迁移两张指定图片到另一个存储
{
  "targetStorageConfigId": 11,
  "targetPrefix": "archive",
  "preserveDirectory": true,
  "preserveFileName": true,
  "deleteSource": true,
  "imageIds": [1, 2]
}

// 迁移某个来源存储下的全部图片（无数量上限）
{
  "targetStorageConfigId": 11,
  "targetPrefix": "archive",
  "sourceStorageConfigId": 10,
  "migrateAll": true
}
```

返回任务状态（刚开始通常是 PENDING / RUNNING）：

```json
{
  "taskId": "1da27935faf3488eb669144c58093e27",
  "status": "RUNNING",
  "sourceName": "S3 源",
  "targetName": "S3 目标",
  "concurrency": 6,
  "total": 1200,
  "done": 300,
  "succeeded": 300,
  "failed": 0,
  "skipped": 0,
  "percent": 25,
  "currentFile": "seed300.png",
  "startedAt": 1760000000000,
  "errors": [
    {
      "reason": "下载源文件失败 (HTTP 404)：<地址>",
      "count": 9,
      "samples": ["lost1.png", "lost2.png", "lost3.png"]
    },
    {
      "reason": "该图片没有可用的访问地址，无法读取原始内容",
      "count": 3,
      "samples": ["nourl1.png", "nourl2.png", "nourl3.png"]
    }
  ]
}
```

```status` 取值：`PENDING` / `RUNNING` / `SUCCESS` / `PARTIAL`（部分失败）/ `FAILED` / `CANCELLED`。

> **同仓库换目录会自动优化**：当所有图片都来自同一个存储配置、且它与目标指向同一个 GitHub 仓库时，
> 迁移不走「下载 + 上传 + 删源」，而是让新 tree 引用文件原有的 blob（Git Data API），
> 顺带把旧路径删除。文件不落盘、不产生重复文件，API 调用数从「每文件 3 次」降到「每批 2 次 + 1 次」。
> 跨仓库 / 跨云存储时仍走原有的下载上传路径。

### GET /api/images/migrate

后台迁移任务列表（新的在前），用于任务面板。每个用户的记录最多保留 50 条，存在内存里，服务重启后清空。

@@@json
[
  {
    "taskId": "7cb72ee1faf3488eb669144c58093e27",
    "status": "RUNNING",
    "sourceName": "S3 源",
    "targetName": "GitHub 目标",
    "concurrency": 6,
    "total": 4980,
    "done": 1200,
    "succeeded": 1200,
    "failed": 0,
    "skipped": 0,
    "percent": 24,
    "currentFile": "IMG_2024.png",
    "startedAt": 1760000000000,
    "errors": [
    {
      "reason": "下载源文件失败 (HTTP 404)：<地址>",
      "count": 9,
      "samples": ["lost1.png", "lost2.png", "lost3.png"]
    },
    {
      "reason": "该图片没有可用的访问地址，无法读取原始内容",
      "count": 3,
      "samples": ["nourl1.png", "nourl2.png", "nourl3.png"]
    }
  ]
  }
]
@@@

### GET /api/images/migrate/{taskId}

查询单个任务状态，用于轮询。

### POST /api/images/migrate/{taskId}/cancel

请求取消，当前正在处理的那一张会先跑完。

> 任务状态只保存在内存里，服务重启后查不到（已迁移的图片记录不受影响）。
> 单张失败不会中断任务。失败原因会**按原因归并**：同一种原因的合并成一条，
> 给出完整失败张数（`count`）+ 最多 10 个示例文件名（`samples`），按失败数量从多到少排列。
> 原因里的 URL 会被归一化成 `<地址>`，避免同一种错误因为地址不同被拆开。

## 仓库目录整理（临时功能）

把 GitHub 仓库某个目录下的图片文件按系统存储规则搬到另一个目录。仅支持 GitHub。

同仓库移动**不重新下载上传**：GitHub 的 Git Data API 允许新 tree 直接引用原有 blob 的 SHA，
整个过程约 6 次 API 调用、一个提交：

```text
GET   /git/trees/{branch}?recursive=1   拿到所有文件路径 + blob SHA
GET   /git/ref/heads/{branch}           拿到当前提交
GET   /git/commits/{sha}                拿到当前 tree
POST  /git/trees                        新 tree（新路径引用旧 SHA，旧路径 sha=null 即删除）
POST  /git/commits                      新提交
PATCH /git/refs/heads/{branch}          移动分支引用
```

### GET /api/images/repo-move/diagnose

自检：把这条链路上每一环单独验一遍，**全部只读**，用于排查"执行了但没动静"这类静默问题。
参数与 `execute` 相同（用 query 传）。

返回一个检查项数组：

```json
[
  { "name": "存储配置", "ok": true, "detail": "Github图床 / GITHUB" },
  { "name": "解析后的目录", "ok": true, "detail": "源 = [api/]  目标 = [api/images/]  包含子目录 = false  保留原名 = true  分层 = false" },
  { "name": "Token 有效性", "ok": true, "detail": "登录身份 qxFly" },
  { "name": "仓库写权限", "ok": true, "detail": "有 push 权限  默认分支 = main  私有 = false" },
  { "name": "分支 main", "ok": true, "detail": "HEAD = 4970e9b5" },
  { "name": "最近提交", "ok": true, "detail": "没发现本工具创建的提交；4970e9b5 chore(pichost): upload …" },
  { "name": "文件树", "ok": true, "detail": "文件总数 6257  截断 = false  以 [api/] 开头的 6255  其中符合条件的图片 4979" },
  { "name": "API 配额", "ok": true, "detail": "剩余 5000 / 5000" },
  { "name": "本次将移动", "ok": true, "detail": "可移动 4979 个，重名跳过 0 个；示例 api/1.png -> api/images/1.png" }
]
```

### POST /api/images/repo-move/preview

只扫描不提交。参数同下，`dryRun` 固定为 true。

### POST /api/images/repo-move/execute

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| storageConfigId | 是 | 必须是 GITHUB 类型的存储配置 |
| sourceDirectory | 否 | 源目录，默认 `api`。写法宽容：`main/api`、`owner/repo/main/api`、完整 GitHub URL 都会被解析成 `api/`（分支名自动去掉） |
| targetDirectory | 否 | 目标目录，默认 `api/images`，同样宽容 |
| includeSubdirectories | 否 | 默认 false，只处理直接放在源目录下的文件 |
| keepFileName | 否 | 默认 **true**：只换目录、保留原文件名。false 时用 uuid 重新命名 |
| layered | 否 | 默认 false：文件直接放进目标目录。true 时再套一层 `日期/分片/` |
| maxFiles | 否 | 本次最多搬多少个（0 = 不限制）。大批量时建议传 500，搬完立刻提交，失败只损失这一批 |

```json
{
  "dryRun": false,
  "total": 7,
  "moved": 7,
  "remaining": 0,
  "commits": 1,
  "commitSha": "a1b2c3d…",
  "commitUrl": "https://github.com/owner/repo/commit/a1b2c3d…",
  "samples": ["api/a.png  ->  api/images/2026/10/13/001/8775702f….png"],
  "shards": ["2026/10/13/001 -> 2 个", "2026/10/13/002 -> 5 个"],
  "message": "已移动 7 个文件，共 1 个提交，7 条图库记录已同步更新"
}
```

```json
// 保留原名、不分层（默认）
{
  "total": 2,
  "collisions": 1,
  "samples": ["api/b.jpg  ->  api/images/b.jpg"],
  "collisionSamples": ["api/a.png -> api/images/a.png"]
}
```

> **大批量请分批执行**：带 `maxFiles=500` 反复调用即可，`remaining` 会告诉你源目录还剩多少，
> 变成 0 就搬完了。每批搬完都会更新分支引用并同步数据库，所以中途失败不会丢已完成的进度。
>
> GitHub 的 tree 接口在数据量大时会返回 `502` / `422`，偶尔还会**返回 200 但响应体里没有 sha**；
> 工具会校验 sha 并重试（`REPO_MOVE_RETRY`，默认 6 次）。
>
> 保留原文件名时，目标位置已存在同名文件的会被**跳过**（不会覆盖），计入 `collisions`，
> 具体路径在 `collisionSamples` 里（最多 10 条）。要避免重名可以勾选 `layered` 套一层日期/分片。
>
> **执行时如果匹配不到任何文件会直接返回 `code 400`**（不会静默成功），错误信息里会带上解析后的源目录，
> 方便核对是不是把分支名写进去了。预览则正常返回 0。
>
> 执行成功后会同步更新图库记录的 `objectKey` 与 `url`。
> 仓库里会多出一个提交，可以用 `git revert` 回退。
> 预览使用「只看不占」的分片规划，不会消耗分片号，因此反复预览后再执行结果一致。

## 仓库重复文件清理（临时功能）

清理 GitHub 仓库里同名的重复图片。仅支持 GitHub。

### POST /api/images/repo-dedupe/preview

只分析不删除（`dryRun` 固定为 true）。参数同下。

### POST /api/images/repo-dedupe/execute

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| storageConfigId | 是 | 必须是 GITHUB 类型的存储配置 |
| directory | 否 | 只在这个目录下查找，默认 `api/images` |
| forceDeleteSuffixed | 否 | 默认 false：只删内容相同的。true 时连"内容不同的 -N 后缀"也删 |

```json
{
  "dryRun": true,
  "scanned": 6255,
  "duplicateGroups": 872,
  "sameShaGroups": 872,
  "diffShaGroups": 0,
  "suffixedWithBase": 174,
  "orphanSuffixed": 0,
  "toDelete": 1256,
  "deleted": 0,
  "samples": ["api/images/2026/10/13/008/76533140_p0.png"],
  "diffSamples": [],
  "orphanSamples": [],
  "message": "分析完成：扫描 6255 个图片，872 组重名，其中内容完全相同的 872 组；建议删除 1256 个"
}
```

判断规则：

- 按「去掉尾部 `-N` 后缀的文件名」分组，`N` 只认 1~2 位数字（`-1` ~ `-99`）；
  `(1)`、`-2024` 这类不参与
- 后缀反复剥离，`foo-1-1.png` 与 `foo.png` 同组
- 组内先比对 **blob SHA**：只有内容一致才删。`diffShaGroups` 就是"同名但内容不同"的组数，这些一律不删
- 每组的保留项优先选图库里已有记录的那一份；被删文件的库记录，若保留项没有记录就改写过去，否则删除
- `orphanSuffixed` 是带 `-N` 后缀但没有同名基名的数量，默认不删（可能是唯一的图）

## 系统

### GET /api/system/info

```json
{
  "name": "PicHost",
  "version": "0.1.0",
  "storageTypes": ["GITHUB", "CLOUDFLARE_R2", "ALIYUN_OSS", "TENCENT_COS", "QINIU_KODO", "SMMS", "IMGBB", "S3_COMPATIBLE"],
  "maxUploadSize": 31457280,
  "allowedExtensions": ["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "tiff"],
  "dedupeEnabled": true
}
```

### GET /actuator/health

无需鉴权，用于容器健康检查：

```json
{ "status": "UP" }
```
