# 存储后端配置指南

所有存储后端都在「存储设置 → 新增存储后端」中配置。敏感字段（Token / SecretKey 等）会 AES-GCM 加密入库，页面上只显示 `******`。

通用字段：

| 字段 | 说明 |
| --- | --- |
| 路径前缀 pathPrefix | 对象键前缀，最终键形如 `images/2026/01/01/<uuid>.png`，留空则放根目录 |
| 自定义域名 customDomain | 自定义 CDN 域名，留空用云厂商默认域名 |

---

## 1. GitHub（GITHUB）

两种模式，通过 `mode` 切换。

**contents 模式**（默认）：图片作为仓库文件提交。仓库不要太大（建议 1GB 以内），可搭配 jsDelivr 加速。

| 参数 | 必填 | 示例 |
| --- | --- | --- |
| owner | 是 | `octocat` |
| repo | 是 | `my-images` |
| token | 是 | `ghp_xxxxxxxx` |
| branch | 否 | `main`，必须已存在 |
| cdnPrefix | 否 | `https://cdn.jsdelivr.net/gh/octocat/my-images@main` |
| pathPrefix | 否 | `images` |

Token 权限：classic 勾选 `repo`；fine-grained 授予目标仓库的 **Contents: Read and write**。

**releases 模式**：图片作为 Release 附件，不占用仓库体积，适合大量图片。额外参数 `releaseTag`（默认 `pichost`），Tag 不存在时会自动创建 Release。

> 注意：Contents API 单文件建议不超过 1MB；更大图片请用 releases 模式或其它对象存储。

---

## 2. Cloudflare R2（CLOUDFLARE_R2）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| accountId | 是 | 控制台 R2 概览页的 Account ID |
| accessKeyId / secretAccessKey | 是 | R2 → Manage R2 API Tokens 创建，密钥只展示一次 |
| bucket | 是 | 存储桶名 |
| endpoint | 否 | 留空自动使用 `https://<accountId>.r2.cloudflarestorage.com` |
| publicBaseUrl | 是 | 公网访问前缀 |

R2 本身不提供公开读取，必须二选一：

1. 在桶设置里开启 **Public Development URL**（`https://pub-xxxx.r2.dev`），填到 `publicBaseUrl`；
2. 绑定自定义域名（推荐，配合 Cloudflare CDN），如 `https://img.example.com`。

---

## 3. 阿里云 OSS（ALIYUN_OSS）

| 参数 | 必填 | 示例 |
| --- | --- | --- |
| endpoint | 是 | `oss-cn-hangzhou.aliyuncs.com` |
| accessKeyId / accessKeySecret | 是 | 建议用 RAM 子账号，仅授权 OSS |
| bucket | 是 | `my-images` |
| customDomain | 否 | `https://img.example.com` |

桶的读写权限设为**公共读**，否则返回的直链无法匿名访问。

---

## 4. 腾讯云 COS（TENCENT_COS）

| 参数 | 必填 | 示例 |
| --- | --- | --- |
| region | 是 | `ap-guangzhou` |
| secretId / secretKey | 是 | 建议用子账号密钥 |
| bucket | 是 | `my-images-1250000000`（**必须带 APPID 后缀**） |
| customDomain | 否 | `https://img.example.com` |

---

## 5. 七牛云 Kodo（QINIU_KODO）

| 参数 | 必填 | 示例 |
| --- | --- | --- |
| accessKey / secretKey | 是 | 个人中心 → 密钥管理 |
| bucket | 是 | `pichost` |
| domain | 是 | `http://cdn.example.com`（必须绑定并备案的域名） |
| region | 是 | `z0` 华东 / `z1` 华北 / `z2` 华南 / `na0` 北美 / `as0` 亚太 |

七牛不提供默认测试域名给新空间，必须自行绑定域名。

---

## 6. SM.MS（SMMS）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| token | 否 | https://sm.ms/home/apitoken 获取 |

- 不填 Token：匿名上传，可以传但**无法删除**。
- 填 Token：可删除、可查看历史。
- 同一张图片重复上传时，SM.MS 直接返回已有地址，系统会自动复用。

---

## 7. ImgBB（IMGBB）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| apiKey | 是 | https://api.imgbb.com/ 免费申请 |
| expiration | 否 | 过期秒数，`0` 表示永久，可选 60 ~ 15552000 |

按 Base64 提交，单张上限 32MB。
**ImgBB 官方公开 API 没有删除接口**，系统会在删除时打印日志并提示 `delete_url`，需要人工去网页删除。

---

## 8. S3 兼容存储（S3_COMPATIBLE）

适用于 MinIO、Ceph RGW、Backblaze B2、自建对象网关等。

| 参数 | 必填 | 示例 |
| --- | --- | --- |
| endpoint | 是 | `http://127.0.0.1:9000` |
| accessKeyId / secretAccessKey | 是 | `minioadmin / minioadmin` |
| bucket | 是 | `pichost` |
| region | 否 | MinIO 可随意填，默认 `us-east-1` |
| publicBaseUrl | 是 | `http://127.0.0.1:9000/pichost` |
| pathStyle | 否 | 自建服务通常需要开启（默认开） |

用本仓库的 `docker compose up -d` 会自动创建并公开读的 `pichost` 桶，可直接用以下配置验证：

```text
endpoint        http://127.0.0.1:9000
accessKeyId     minioadmin
secretAccessKey minioadmin
bucket          pichost
region          us-east-1
publicBaseUrl   http://127.0.0.1:9000/pichost
pathStyle       true
pathPrefix      images
```

---

## 排查建议

| 现象 | 排查方向 |
| --- | --- |
| 测试连接报 403 / SignatureDoesNotMatch | AK/SK 不匹配，或 `region`/`pathStyle` 设置有误 |
| 上传成功但直链 404 / 403 | 桶未设为公共读，或 `publicBaseUrl`/`customDomain` 配错 |
| 上传成功但直链打不开 | 该后端需要绑定域名（R2 / 七牛云）后才能公网访问 |
| GitHub 报 404 | 仓库名错、Token 无该仓库权限、`branch` 不存在 |
| GitHub 报 422 | 文件已存在（本系统用 UUID 命名，正常不会出现）或文件超过 1MB |
| SM.MS 上传失败 | 匿名上传频率受限，配置 Token 可提升额度 |

> 换过 `CRYPTO_KEY` 后旧配置无法解密，表现为「测试连接失败」，重新录入密钥即可。
