package com.pichost.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * PicHost 业务配置，对应 application.yml 中的 app.* 节点。
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
    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("*"));
    }
}
