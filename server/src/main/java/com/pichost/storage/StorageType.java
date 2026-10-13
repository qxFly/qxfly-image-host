package com.pichost.storage;

import com.pichost.common.BizException;

import java.util.Arrays;
import java.util.List;

/**
 * 支持的存储后端类型及其配置项定义。
 */
public enum StorageType {

    GITHUB("GitHub", "仓库 / Releases",
            "使用 GitHub 仓库 Contents API 或 Release 附件作为图床，可搭配 jsDelivr 等 CDN 加速。",
            List.of(
                    StorageField.req("owner", "仓库所有者", "GitHub 用户名或组织名，例如 octocat"),
                    StorageField.req("repo", "仓库名称", "用于存放图片的仓库，例如 my-images"),
                    StorageField.pwd("token", "Personal Access Token", "classic 需要 repo 权限，fine-grained 需要 Contents 读写权限"),
                    StorageField.sel("mode", "存储模式", "contents", List.of("contents", "releases"),
                            "contents 以仓库文件存储；releases 以 Release 附件存储，不受仓库体积限制"),
                    StorageField.opt("branch", "分支", "main", "contents 模式使用的分支"),
                    StorageField.opt("releaseTag", "Release 标签", "pichost", "releases 模式使用的 Tag，不存在时自动创建"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀，留空则放在根目录"),
                    StorageField.opt("cdnPrefix", "CDN 前缀", "", "例如 https://cdn.jsdelivr.net/gh/owner/repo@main，留空则使用 raw.githubusercontent.com"),
                    StorageField.opt("apiBase", "API 地址", "https://api.github.com", "GitHub Enterprise 可改为自建地址"))),

    CLOUDFLARE_R2("Cloudflare R2", "R2",
            "兼容 S3 协议，零出口流量费；需绑定自定义域或开启 r2.dev 才能公网访问。",
            List.of(
                    StorageField.req("accountId", "账户 ID", "Cloudflare 控制台 R2 概览页右侧的 Account ID"),
                    StorageField.req("accessKeyId", "Access Key ID", "R2 -> Manage R2 API Tokens 创建"),
                    StorageField.pwd("secretAccessKey", "Secret Access Key", "创建 API Token 时只展示一次"),
                    StorageField.req("bucket", "存储桶", "例如 my-images"),
                    StorageField.opt("endpoint", "Endpoint", "", "留空自动使用 https://<accountId>.r2.cloudflarestorage.com"),
                    StorageField.req("publicBaseUrl", "公网访问前缀", "例如 https://img.example.com 或 https://pub-xxxx.r2.dev"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀"),
                    StorageField.opt("region", "区域", "auto", "R2 固定为 auto"))),

    ALIYUN_OSS("阿里云 OSS", "OSS",
            "阿里云对象存储，支持自定义域名与 CDN 回源。",
            List.of(
                    StorageField.req("endpoint", "Endpoint", "例如 oss-cn-hangzhou.aliyuncs.com"),
                    StorageField.req("accessKeyId", "AccessKey ID", "建议使用 RAM 子账号并只授予 OSS 权限"),
                    StorageField.pwd("accessKeySecret", "AccessKey Secret", "RAM 子账号的密钥"),
                    StorageField.req("bucket", "存储桶", "例如 pichost-images"),
                    StorageField.opt("customDomain", "自定义域名", "", "例如 https://img.example.com，留空使用 OSS 默认域名"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀"))),

    TENCENT_COS("腾讯云 COS", "COS",
            "腾讯云对象存储，存储桶名称需带 APPID 后缀。",
            List.of(
                    StorageField.req("region", "地域", "例如 ap-guangzhou"),
                    StorageField.req("secretId", "SecretId", "建议使用子账号密钥"),
                    StorageField.pwd("secretKey", "SecretKey", "子账号密钥"),
                    StorageField.req("bucket", "存储桶", "格式为 名称-APPID，例如 pichost-1250000000"),
                    StorageField.opt("customDomain", "自定义域名", "", "例如 https://img.example.com，留空使用 COS 默认域名"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀"))),

    QINIU_KODO("七牛云 Kodo", "Kodo",
            "七牛云对象存储，必须绑定加速域名后通过域名访问。",
            List.of(
                    StorageField.req("accessKey", "AccessKey", "七牛云个人中心的密钥管理"),
                    StorageField.pwd("secretKey", "SecretKey", "七牛云个人中心的密钥管理"),
                    StorageField.req("bucket", "存储空间", "例如 pichost"),
                    StorageField.req("domain", "访问域名", "例如 http://cdn.example.com，需在七牛绑定并备案"),
                    StorageField.sel("region", "存储区域", "z0", List.of("z0", "z1", "z2", "na0", "as0"),
                            "z0 华东 / z1 华北 / z2 华南 / na0 北美 / as0 亚太"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀"))),

    SMMS("SM.MS", "免费图床",
            "免费公共图床，匿名即可上传；配置 Token 后可删除图片。",
            List.of(
                    StorageField.optPwd("token", "API Token", "https://sm.ms/home/apitoken 获取；留空为匿名上传（无法删除）"))),

    IMGBB("ImgBB", "免费图床",
            "免费公共图床，按 Base64 上传，单张限制 32MB。",
            List.of(
                    StorageField.pwd("apiKey", "API Key", "https://api.imgbb.com/ 免费申请"),
                    StorageField.num("expiration", "过期时间(秒)", "0", "0 表示永久保存，可选 60 ~ 15552000"))),

    S3_COMPATIBLE("S3 兼容存储", "MinIO 等",
            "任何兼容 S3 协议的对象存储：MinIO、Ceph、Backblaze B2、自建网关等。",
            List.of(
                    StorageField.req("endpoint", "Endpoint", "例如 http://127.0.0.1:9000"),
                    StorageField.req("accessKeyId", "Access Key", "对象存储的访问密钥"),
                    StorageField.pwd("secretAccessKey", "Secret Key", "对象存储的私钥"),
                    StorageField.req("bucket", "存储桶", "例如 pichost"),
                    StorageField.opt("region", "区域", "us-east-1", "MinIO 可随意填写，签名需要"),
                    StorageField.req("publicBaseUrl", "公网访问前缀", "例如 http://127.0.0.1:9000/pichost"),
                    StorageField.sw("pathStyle", "使用 Path-Style 访问", true, "MinIO 等自建服务通常需要开启"),
                    StorageField.opt("pathPrefix", "路径前缀", "images", "图片存放的目录前缀")));

    private final String label;
    private final String shortLabel;
    private final String description;
    private final List<StorageField> fields;

    StorageType(String label, String shortLabel, String description, List<StorageField> fields) {
        this.label = label;
        this.shortLabel = shortLabel;
        this.description = description;
        this.fields = fields;
    }

    public String getLabel() {
        return label;
    }

    public String getShortLabel() {
        return shortLabel;
    }

    public String getDescription() {
        return description;
    }

    public List<StorageField> getFields() {
        return fields;
    }

    public List<StorageField> getSecretFields() {
        return fields.stream().filter(StorageField::secret).toList();
    }

    /** 公开图床类后端（无需用户自建资源） */
    public boolean isPublicImageHost() {
        return this == SMMS || this == IMGBB;
    }

    public static StorageType of(String name) {
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new BizException("不支持的存储类型: " + name));
    }
}
