package com.qxfly.imagehost.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * qxfly-image-host 业务配置，对应 application.yml 中的 app.* 节点。
 */
@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    /** 存储配置中敏感字段的 AES 加密口令 */
    private String cryptoKey = "pichost-dev-crypto-key-change-me-in-production";
    private Upload upload = new Upload();
    /** 相同用户上传相同内容时复用已有图片记录 */
    private boolean dedupeEnabled = true;
    private InitAdmin initAdmin = new InitAdmin();
    private Cors cors = new Cors();
    private Migrate migrate = new Migrate();
    private RepoMove repoMove = new RepoMove();
    private Register register = new Register();
    private Trash trash = new Trash();
    private LocalStorage localStorage = new LocalStorage();
    private Thumb thumb = new Thumb();
    /**
     * 本服务的对外基地址（例如 https://img.example.com），用于生成缩略图与本地存储直链。
     * 留空则用「当前请求的地址」，再不行退回 http://127.0.0.1:<server.port>。
     */
    private String publicBaseUrl = "";

    @Data
    public static class Jwt {
        private String secret = "pichost-dev-jwt-secret-key-change-me-in-production-2024";
        private long expireMinutes = 10080L;
    }

    @Data
    public static class Upload {
        private long maxSize = 30L * 1024 * 1024;
        /**
         * 单个目录最多放多少个文件，超过就写到下一个分片目录（001/002/...）。
         * 设为 0 或负数则关闭分片。
         */
        private int shardSize = 500;
        /** 从存储导入时最多扫描多少个远端文件，防止一次拉爆内存 */
        private int importMaxFiles = 20000;
        /**
         * 上传时先把 multipart 落到这个目录，再整条链路按流处理。
         * 留空用系统临时目录（java.io.tmpdir）；目录不存在会自动创建。
         */
        private String tempDir = "";
        /**
         * GitHub contents 模式单文件上限（字节）：Contents API 必须整份 Base64 提交，
         * 内容只能进内存，所以留一道上限；更大的文件请用 Releases 模式或对象存储 / 本地存储。
         * 0 或负数表示不额外限制（仍受 GitHub 自身 100MB 限制）。
         */
        private long githubInlineLimit = 95L * 1024 * 1024;
        private List<String> allowedExtensions = new ArrayList<>(List.of(
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "tiff"));
    }

    @Data
    public static class InitAdmin {
        private boolean enabled = true;
        private String username = "admin";
        private String password = "admin123";
        private String nickname = "超级管理员";
    }

    @Data
    public static class RepoMove {
        /**
         * 每个提交最多处理多少个文件（每个文件产生「新增 + 删除」两条 tree 记录）。
         * 批次太大会被 GitHub 拒绝：实测 1000 个文件（2000 条记录）会返回 502 Server Error。
         */
        private int batchFiles = 200;
        /** 创建 tree / commit 遇到 5xx / 422 时的重试次数 */
        private int retryTimes = 6;
    }

    @Data
    public static class Migrate {
        /** 迁移时的文件级并发数（每张图要下载 + 上传两次网络往返，并发能成倍提速） */
        private int concurrency = 6;
        /** 命中限流 / 5xx / 超时时的重试次数 */
        private int retryTimes = 2;
    }

    @Data
    public static class Trash {
        /**
         * 回收站目录（仓库根目录下的独立目录）。
         * 逻辑删除时会把仓库里的文件搬到这里，恢复时再搬回原位置；物理删除才真正删掉。
         */
        private String prefix = "_trash";
    }

    @Data
    public static class Register {
        /**
         * 是否允许任何人自助注册。
         * 默认 false：账号由超级管理员在「用户管理」里创建；注册出来的账号一律是普通用户。
         */
        private boolean enabled = false;
    }

    @Data
    public static class Thumb {
        /** 是否给没有原生缩略图的图片生成缩略图（本地缓存 + 签名直链） */
        private boolean enabled = true;
        /** 图库默认用多宽的缩略图 */
        private int defaultWidth = 400;
        /** 允许请求的宽度区间 */
        private int minWidth = 32;
        private int maxWidth = 1600;
        /** 缓存目录，留空用「工作目录 + thumbs」 */
        private String cacheDir = "thumbs";
        /**
         * 超过这个大小的图片不生成缩略图（原图要整份进内存，太大就退回原图直链）。
         */
        private long maxSourceSize = 20L * 1024 * 1024;
    }

    @Data
    public static class LocalStorage {
        /**
         * 本地存储的默认目录（存储配置里留空时用这个）。
         * 相对路径以服务进程的工作目录为基准；目录不存在会自动创建。
         */
        private String baseDir = "uploads";
        /**
         * 对外访问前缀。留空则自动用「当前请求的地址」（你用什么地址访问本服务就用什么），
         * 再取不到时退回 http://127.0.0.1:<server.port>。
         * 部署在 Nginx / CDN 后面时建议显式配置，例如 https://img.example.com。
         */
        private String publicBaseUrl = "";
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    }
}
