# qxfly-image-host API 文档

Base URL：`http://127.0.0.1:8080`（端口可改：`config/local.env` 里的 `BACKEND_PORT`，或 `start.ps1 -BackendPort 8081`）
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

### 角色与权限

系统只有两个角色：

| 角色 | 说明 | 权限 |
| --- | --- | --- |
| `SUPER_ADMIN` | 超级管理员 | 全部接口 |
| `USER` | 普通用户 | 浏览 / 查看 / 下载，以及 `/api/auth` 下的个人资料与改密 |

- 管理类接口（上传、删除、迁移、导入、存储配置增删改、账号管理）对普通用户返回
  `401`（未登录）或 `403 / "该操作仅超级管理员可用"`
- **超级管理员只能通过数据库赋予**，没有任何接口可以修改角色：
  `UPDATE sys_user SET role = 'SUPER_ADMIN' WHERE username = 'xxx';`
- 鉴权时**以数据库为准**（每次请求都会回库确认账号存在、未禁用、并取最新角色），
  所以改库提权 / 降权 / 禁用立即生效，不需要重新登录

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

创建**普通用户**账号（角色固定为 `USER`，请求体里没有也不需要 roles 字段）。

```json
{ "username": "alice", "password": "123456", "nickname": "Alice", "email": "a@b.com" }
```

用户名规则：3-32 位字母、数字、下划线或短横线。

默认情况下该接口**仅超级管理员可用**（用于建账号）；把 `REGISTER_ENABLED=true` 打开后匿名也能调用，
注册出来同样是普通用户。

### GET /api/auth/register-enabled

免鉴权。登录页据此决定要不要显示「注册」入口。

```json
{ "enabled": false }
```

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

## CDN 线路

> 整段都在 `/api/storage-configs/**` 下，**仅超级管理员**可访问。
> 图片列表里的 `previewUrl / downloadUrl / copyUrl` 由服务端按这里配置的默认线路解析好再返回，
> 普通用户不需要（也不能）读这些接口。

每个存储配置可以有多条线路：

- **内置预设**：按存储类型生成。GitHub contents 模式提供 `gh-proxy` / `ghproxy` / `jsdelivr-cf` / `jsdelivr`；
  其他类型（含本地存储）只有 `origin` —— 它们的原始地址本身就是可直连的访问地址
- **自定义**：名称 + 地址前缀，前缀支持 `{owner}` / `{repo}` / `{branch}` 占位符
- `origin` 是保留 key，表示存储后端的原始直链，永远存在、不可删除

### GET /api/storage-configs/{id}/cdns

```json
[
  {
    "key": "origin",
    "name": "原始地址（存储后端直链）",
    "template": "",
    "example": "https://raw.githubusercontent.com/owner/repo/main/api/images/2026/10/13/001/a.png",
    "origin": true,
    "builtin": true,
    "enabled": true,
    "previewDefault": true,
    "downloadDefault": true,
    "copyDefault": false
  },
  {
    "key": "jsdelivr",
    "name": "jsDelivr（全球加速）",
    "template": "https://cdn.jsdelivr.net/gh/owner/repo@main",
    "example": "https://cdn.jsdelivr.net/gh/owner/repo@main/api/images/2026/10/13/001/a.png",
    "origin": false,
    "builtin": true,
    "enabled": true,
    "previewDefault": false,
    "downloadDefault": false,
    "copyDefault": true
  }
]
```

`example` 是用该配置下最近一张图片的对象键拼出来的真实地址，前端直接拿它测延时。

### POST /api/storage-configs/{id}/cdns

新增自定义线路（最多 20 条）。

```json
{ "name": "我的加速域名", "template": "https://cdn.example.com/{owner}", "enabled": true }
```

地址前缀必须以 `http://` 或 `https://` 开头。返回更新后的完整线路列表。

### PUT /api/storage-configs/{id}/cdns/{key}

修改自定义线路（内置预设不可修改，只能调整「默认」）。请求体同上。

### DELETE /api/storage-configs/{id}/cdns/{key}

删除自定义线路。如果它正好是某个用途的默认线路，该用途会自动回退到 `origin`。

### PUT /api/storage-configs/{id}/cdns/defaults

设置三个用途各自的默认线路；值为线路 key，`null` 或 `origin` 表示原始地址。

```json
{ "preview": "jsdelivr", "download": null, "copy": "jsdelivr" }
```

## 账号管理

> 整个 `/api/users/**` 仅超级管理员可访问；普通用户调用返回 `403`。
> 这里没有「修改角色」的接口 —— 新建的账号一律是普通用户，超级管理员只能改数据库赋予。

### GET /api/users

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| page | 1 | 页码 |
| size | 20 | 每页条数（上限 200） |
| keyword | - | 按用户名或昵称模糊筛选 |

```json
{
  "total": 3,
  "page": 1,
  "size": 20,
  "pages": 1,
  "records": [
    {
      "id": 1,
      "username": "admin",
      "nickname": "超级管理员",
      "email": null,
      "role": "SUPER_ADMIN",
      "roleLabel": "超级管理员",
      "status": 1,
      "createdAt": "2026-10-13T14:18:03"
    }
  ]
}
```

### POST /api/users

新建账号，**角色固定为普通用户**。

```json
{ "username": "alice", "password": "123456", "nickname": "Alice", "email": "a@b.com" }
```

### PUT /api/users/{id}

修改账号资料（昵称 / 邮箱）。用户名是登录凭据、角色只能改数据库，所以这两个字段不在请求体里。

```json
{ "nickname": "新昵称", "email": "new@example.com" }
```

- 昵称留空则回退成用户名
- 超级管理员的账号不能在这里修改：`403 超级管理员的资料请他自己在「个人中心」修改`

### PUT /api/users/{id}/status

启用 / 禁用账号。禁用后该账号手上的 token **立即失效**。

```json
{ "status": 0 }
```

- 不能修改超级管理员账号：`403 超级管理员的账号不能在这里修改`
- 不能禁用自己：`400 不能禁用当前登录的账号`

### PUT /api/users/{id}/password

重置密码。

```json
{ "newPassword": "newpass123" }
```

超级管理员账号不能在这里重置，需在「个人中心」或数据库里改。

### DELETE /api/users/{id}

删除账号。以下情况会被拒绝（业务错误码 `403` / `400`）：

- 目标是超级管理员：`超级管理员的账号不能删除`
- 目标是当前登录账号：`不能删除当前登录的账号`
- 该账号名下还有图片：`该账号名下还有 N 张图片，先清理或迁移后再删除`

## 存储配置

### GET /api/storage-configs/types

返回所有存储类型及其表单字段定义，前端据此动态渲染配置表单。

```json
[
  {
    "name": "LOCAL",
    "label": "本地存储",
    "shortLabel": "本机磁盘",
    "description": "把图片保存在运行本服务的这台机器上，由本服务直接提供访问地址（/files/...）……",
    "fields": [
      { "key": "baseDir", "label": "存储目录", "type": "text", "required": false, "secret": false, "defaultValue": "uploads", "options": [], "help": "绝对路径或相对服务进程工作目录的路径" },
      { "key": "publicBaseUrl", "label": "对外访问前缀", "type": "text", "required": false, "secret": false, "defaultValue": "", "options": [], "help": "留空则自动使用访问本服务所用的地址" },
      { "key": "pathPrefix", "label": "路径前缀", "type": "text", "required": false, "secret": false, "defaultValue": "images", "options": [], "help": "图片存放的目录前缀" }
    ]
  },
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

> 从本节开始：**浏览 / 查看 / 下载类接口对所有登录用户开放**（`GET /api/images`、`/api/images/{id}`、
> `/api/images/directories`、`/api/images/stats`、`/api/images/{id}/download`、`POST /api/images/download/batch`），
> 其余**写操作**（上传、删除、迁移、导入、仓库工具）都**只有超级管理员**能调用，普通用户返回 403。
>
> **可见范围**：图片按账号存在 `image_file.user_id` 上。超级管理员看到自己名下的图片；
> 普通用户是只读账号、自己又不能上传，所以看到的是**超级管理员名下的图库**（系统图库），
> 下载时用图片所属账号的存储配置取内容（`GET /api/storage-configs` 对普通用户仍只返回他自己的配置，通常是空列表）。

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
  "previewUrl": "http://127.0.0.1:9000/pichost/images/2026/01/01/fb32e615790743309e2800e6d0afdc0e.png",
  "downloadUrl": "http://127.0.0.1:9000/pichost/images/2026/01/01/fb32e615790743309e2800e6d0afdc0e.png",
  "copyUrl": "http://127.0.0.1:9000/pichost/images/2026/01/01/fb32e615790743309e2800e6d0afdc0e.png",
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

- `url` 恒为存储后端的原始直链，`markdown` 用的也是它
- `previewUrl / downloadUrl / copyUrl` 是按「存储设置 → CDN 线路」里配置的三个默认线路解析出来的地址；
  没配置线路时三者都等于 `url`。缩略图 / 预览用 `previewUrl`，复制用 `copyUrl`，下载用 `downloadUrl`

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

**删除 = 移入回收站**（逻辑删除）：数据库打删除标记，**仓库里的文件也会被搬进回收站目录**
（默认 `_trash/`，例如 `api/images/2026/10/13/001/x.png` → `_trash/api/images/2026/10/13/001/x.png`）。
所以列表 / 目录树 / 统计 / 详情 / 下载都不再包含它，但可以从回收站恢复。真正的物理删除在回收站里做。

- 搬运走的是 GitHub 的**仓库内引用式搬移**（复用原有 blob 的 SHA），不下载不上传，一次搬运只多一个提交
- 只有 GitHub contents 模式支持；其他后端退化为「只打标记、文件原地保留」
- 记录里会多一个 `trashKey` 字段记录文件在回收站目录里的位置

### POST /api/images/delete/batch

**批量删除**（仅超级管理员）= 批量移入回收站，与单张 `DELETE /api/images/{id}` 语义完全相同。

```json
{ "imageIds": [128, 129] }
```

- 返回移入回收站的张数（`data` 为数字）
- 云端文件逐个搬进回收站目录（GitHub contents 模式引用式搬移零流量；其他后端退化为只打标记）
- **要么全成功、要么整体报错**：`imageIds` 里只要有一个不存在 / 已在回收站 / 不属于你，
  就返回 `404` 并提示有几张找不到，一张也不会删 —— 刷新列表后重试即可
- 重复提交同一个 id 不会重复删除（已在回收站里的查不出来，直接计入上面的「找不到」）

### GET /api/images/trash

回收站列表（仅超级管理员）。

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| page | 1 | 页码 |
| size | 30 | 每页条数（上限 500） |
| keyword | - | 按文件名或对象键模糊筛选 |

返回结构与 `GET /api/images` 相同，记录里多了两个字段：

- `deletedAt`：移入回收站的时间
- `trashKey`：文件在回收站目录里的对象键；为 `null` 表示云端文件还留在原位（非 GitHub 后端，或搬运失败）

### POST /api/images/trash/restore

从回收站恢复（仅超级管理员）。

```json
{ "imageIds": [128, 129] }
```

返回恢复的张数（`data` 为数字）。恢复会先把云端文件从回收站目录搬回原位置（同样零流量），
全都搬成功了才改数据库；只要有搬不回来的就整体不恢复并报错，避免「数据库说恢复了、文件还在回收站目录」的错位状态。
搬运是幂等的，失败后重试即可。

### POST /api/images/trash/delete

**彻底删除**（仅超级管理员）：先调用存储后端删掉云端对象，再删本地记录，**不可恢复**。

```json
{ "imageIds": [128, 129] }
```

- 返回彻底删除的张数
- 删除的是文件**实际所在的位置**：搬进回收站目录的删 `trashKey`，没搬过的删原对象键
- 云端删除失败（例如 ImgBB 官方 API 不支持删除）只记告警日志，本地记录**仍会清理**，
  避免留下「本地没了、云端还在」的孤儿文件
- 只能删回收站里的图片；不在回收站里的 id 会被忽略，全都找不到时返回 `404`

### POST /api/images/trash/clear

清空回收站（仅超级管理员），把回收站里的图片全部彻底删除，返回删除的张数。

### GET /api/images/{id}/links

这张图可用的全部链接，供「选择复制哪种链接」的弹窗使用（连通性与延时由前端自己测）。

```json
[
  { "key": "origin", "name": "原始地址（存储后端直链）", "url": "https://raw.githubusercontent.com/...", "origin": true, "builtin": true, "copyDefault": false },
  { "key": "jsdelivr", "name": "jsDelivr（全球加速）", "url": "https://cdn.jsdelivr.net/gh/...", "builtin": true, "copyDefault": true },
  { "key": "proxy", "name": "原站代理下载（需登录，支持私有存储）", "proxy": true, "builtin": true }
]
```

- 每个条目都带 `markdown`（`![文件名](地址)`）
- `origin = true` 表示存储后端的原始直链
- `proxy = true` 表示服务端代理下载：`url` 为空，前端调用 `GET /api/images/{id}/download`
- 三个 `*Default` 标记对应「设置 → CDN 线路」里选的默认线路

### GET /api/images/{id}/download

下载单张原图。**响应不是 JSON，而是文件流**：`Content-Type` 为该图片的 MIME 类型，
`Content-Disposition: attachment` 给出原文件名（同时带 RFC 5987 写法，中文名不乱码）。

内容由服务端代理取回，所以私有仓库 / 私有桶里的图片也能下载，浏览器端也不用处理跨域：

```bash
curl -H "Authorization: Bearer <token>" -o out.png \
  http://127.0.0.1:8080/api/images/128/download
```

失败时仍按统一结构返回 JSON（`code != 200`）。

### GET /files/{配置ID}/{对象键}

**本地存储（```LOCAL```）的公开直链**，也就是浏览器的 `<img src>` 直接访问的那个地址。

- **匿名可访问**（不在 `/api` 下）：`<img>` 请求带不上 JWT，其他后端（GitHub raw / OSS / jsDelivr）的直链同样是公开的；对象键里有随机 uuid
- 响应是文件流：`Content-Type` 按扩展名给出（`image/png` 等，不带 charset 尾巴），
  并带 `Cache-Control: max-age=604800, public`（对象键含 uuid、内容不会变，可放心缓存）
- 找不到配置 / 类型不是本地存储 / 文件不存在 / 对象键越界（如 ```../```）都返回 **404**
- 其他后端不走这个端点：它们的直链是各自的公网地址

```bash
curl -o out.png http://127.0.0.1:8080/files/24/images/2026/10/13/001/f30a8554109449ffb0b89cd6c7e39e0e.png
```

### POST /api/images/download/batch

把选中的图片打包成一个 zip 流式返回（`Content-Type: application/zip`，
响应头 `X-Image-Count` 是本包的图片数量）。

```json
// 请求
{ "imageIds": [128, 129, 130] }
```

- 一次最多 **500** 张，超出直接报错
- 有任何一张不存在或不属于当前用户时，整包不生成、直接返回 404，
  避免用户拿到一个「悄悄少了几张」的压缩包
- 包内条目名用图片的**原始文件名**；重名自动追加 `-1`、`-2`，
  路径分隔符等非法字符会被替换掉（避免解压时写到目标目录外面）
- 单张取回失败**不会中断整包**，失败的文件会列在包内的 `下载失败清单.txt` 里
- 服务端「边取边写」，内存里最多只放一张图，几百张也不会把服务端撑爆

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
存储类型不支持列举时返回 code = 400 并说明原因（目前支持 GitHub、S3 兼容存储与本地存储）。

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
  { "name": "最近提交", "ok": true, "detail": "没发现本工具创建的提交；4970e9b5 chore(qxfly-image-host): upload …" },
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

## 缩略图

### GET /thumb/{id}?w={宽度}&s={签名}

**匿名可访问**的按需缩略图（`<img>` 带不上 JWT）。签名是 HMAC-SHA256(密钥, `id:宽度`) 的前 32 位十六进制，
稳定不变因此可以长期缓存；签名不对返回 **403**（防止按自增 id 枚举全站图片）。

- `w` 允许 32 ~ 1600，缺省用 ```app.thumb.default-width```（400）
- 响应带 `Cache-Control: max-age=2592000, public`，Content-Type 为 `image/jpeg`（带透明通道的是 `image/png`）
- 图片不存在 / 在回收站 / 格式解不了（SVG、WebP）/ 原图超过 ```app.thumb.max-source-size``` → **404**，前端应退回原图地址
- 图库接口返回的 `thumbUrl` 就是这个地址；已有原生缩略图的后端（SM.MS / ImgBB）直接用它们自己的地址

## 分享链接

### POST /api/shares（仅超级管理员）

```json
{ "title": "国庆照片", "imageIds": [128, 129], "password": "可留空", "expiresInDays": 7 }
```

返回 `{ token, url: "/#/s/{token}", passwordProtected, expiresAt, imageCount, viewCount, ... }`。
只能分享自己可见的图片，一个分享最多 2000 张。

### GET /api/shares ・ DELETE /api/shares/{id}

列出 / 撤销自己创建的分享（撤销后链接立刻失效）。

### POST /api/shares/{token}/view —— **公开**

相册的公开入口，**不需要登录**。需要密码的相册把密码放在请求体里：

```json
{ "password": "xxxx" }
```

| 情况 | 返回 |
| --- | --- |
| 正常 | `200` + `{ title, viewCount, images: [ImageVO...] }`（浏览数 +1） |
| token 不存在 / 已撤销 | `code = 404` |
| 已关闭 / 已过期 | `code = 403`（提示里带过期日期） |
| 需要密码但没给 / 密码错 | `code = 403` |

## 重复图片

### GET /api/images/duplicates?page=&size=（仅超级管理员）

```json
{
  "total": 3, "wastedSize": 1048576, "page": 1, "size": 20, "pages": 1,
  "groups": [ { "hash": "...", "count": 2, "totalSize": 2048,
                "images": [ { "id": 1, "url": "...", "keep": true }, { "id": 2, "keep": false } ] } ]
}
```

### DELETE /api/images/duplicates?hash={可选}

清理：每组保留**最早**的一张，其余**移入回收站**（不是物理删除），返回移入的张数。
不带 `hash` 表示清理全部组。

## 审计日志

### GET /api/audit-logs?page=&size=&keyword=&action=&success=（仅超级管理员）

```json
{ "total": 128, "page": 1, "size": 50, "pages": 3,
  "records": [ { "id": 128, "username": "admin", "action": "LOGIN", "success": 1,
                 "targetType": "USER", "targetId": "1", "detail": "登录成功",
                 "ip": "127.0.0.1", "userAgent": "...", "createdAt": "..." } ] }
```

动作码：```LOGIN / LOGIN_FAILED / LOGIN_LOCKED / UPLOAD / TRASH_MOVE / TRASH_RESTORE / IMAGE_PURGE / TRASH_CLEAR /
USER_CREATE / USER_UPDATE / USER_STATUS / USER_PASSWORD / USER_DELETE / STORAGE_CREATE / STORAGE_UPDATE /
STORAGE_DELETE / STORAGE_DEFAULT / SETTINGS_UPDATE / SHARE_CREATE / SHARE_DELETE```。
读操作不记录；写入失败只打日志，不影响业务。

## 系统

### GET /api/system/info

登录即可访问，前端用它渲染上传页的提示与预校验。

```json
{
  "name": "qxfly-image-host",
  "version": "0.1.0",
  "storageTypes": ["LOCAL", "GITHUB", "CLOUDFLARE_R2", "ALIYUN_OSS", "TENCENT_COS", "QINIU_KODO", "SMMS", "IMGBB", "S3_COMPATIBLE"],
  "maxUploadSize": 31457280,
  "maxUploadSizeText": "30.0 MB",
  "unlimitedUploadSize": false,
  "allowedExtensions": ["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "tiff"],
  "dedupeEnabled": true
}
```

- `maxUploadSize` 是**单张文件大小上限（字节）**；`-1` 表示**无上限**，
  展示时请用 `maxUploadSizeText`（"30.0 MB" / "无上限"），别直接格式化 -1
- 这个值来自「系统设置」，可以随时改（见下），上传接口按改后的值判断

### GET /api/settings

读取系统设置。**仅超级管理员**（普通用户访问返回 403）；普通用户需要的上传上限走 `/api/system/info`。

```json
{
  "maxUploadSize": 31457280,
  "maxUploadSizeText": "30.0 MB",
  "unlimitedUploadSize": false,
  "defaultMaxUploadSize": 31457280,
  "maxUploadSizeLimit": 1099511627776
}
```

### PUT /api/settings

改单张文件大小上限，**立即生效、不用重启**。两种提交方式：

```json
// 1) 设成具体大小（字节，必须 > 0，最大 1TB）
{ "maxUploadSize": 104857600 }

// 2) 设成无上限（此时 maxUploadSize 被忽略）
{ "unlimitedUploadSize": true }

// 3) 登录安全与防盗链（可只提交其中几项）
{ "loginMaxAttempts": 5, "loginLockMinutes": 15,
  "hotlinkEnabled": false, "hotlinkDomains": "a.com,b.com", "hotlinkAllowEmpty": true }
```

返回的 ' + B + 'data' + B + ' 里包含 ' + B + 'maxUploadSizeText' + B + '、' + B + 'loginMaxAttempts' + B + '、' + B + 'loginLockMinutes' + B + '、',
B + 'hotlinkEnabled' + B + '、' + B + 'hotlinkDomains' + B + '、' + B + 'hotlinkAllowEmpty' + B + '。

登录失败锁定的行为：同一账号连续失败达到 ' + B + 'loginMaxAttempts' + B + ' 次后锁定 ' + B + 'loginLockMinutes' + B + ' 分钟，',
'期间即使密码正确也返回 ' + B + '403' + B + '（提示还剩几分钟）；计数在内存里，重启清空；',
'失败与锁定都会写进审计日志。

返回更新后的设置快照（同 GET）。下面是失败场景：

| 请求 | 结果 |
| --- | --- |
| `{ "maxUploadSize": 0 }` | `400` 上限必须大于 0；要取消限制请打开「无上限」 |
| `{}` | `400` 请填写单张文件大小上限，或打开「无上限」 |
| `{ "maxUploadSize": 9999999999999 }` | `400` 上限过大（最大 1.0 TB） |
| 普通用户调用 | `403` |

> 「无上限」时 Servlet 层的 multipart 硬上限也必须放开（默认 `spring.servlet.multipart.max-file-size: -1`），
> 限制只在应用层判断；需要服务端兜底时用环境变量 `UPLOAD_SERVLET_MAX`（例如 `2GB`）。
> 超过 Servlet 硬上限的请求由 `MaxUploadSizeExceededException` 处理器返回 `code = 413`。

### GET /actuator/health

无需鉴权，用于容器健康检查：

```json
{ "status": "UP" }
```
