package com.pichost.storage.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 对象键生成：{前缀}/{yyyy}/{MM}/{dd}/{uuid}.{ext}
 */
public final class ObjectKeys {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private ObjectKeys() {
    }

    public static String build(String pathPrefix, String originalName) {
        return build(pathPrefix, originalName, 0);
    }

    /**
     * 生成对象键：{前缀}/{yyyy}/{MM}/{dd}/[{分片}/]{uuid}.{ext}
     *
     * @param shard 分片序号，从 1 开始。大于 0 时插入一层三位目录（001、002 ...），
     *              用来把单个目录的文件数压在阈值以内；传 0 表示不分片。
     */
    public static String build(String pathPrefix, String originalName, int shard) {
        String shardSegment = shard > 0 ? String.format("%03d/", shard) : "";
        return dayDirectory(pathPrefix) + shardSegment + randomFileName(originalName);
    }

    /** 随机文件名：uuid + 原扩展名 */
    public static String randomFileName(String originalName) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String extension = extension(originalName);
        return extension.isEmpty() ? uuid : uuid + "." + extension;
    }

    /**
     * 取对象键的目录部分（相对 basePrefix）。
     * 例如 objectKey=images/2026/01/01/001/a.png、basePrefix=images/ 时返回 2026/01/01/001/。
     * 没有目录时返回空串。
     */
    public static String relativeDirectory(String objectKey, String basePrefix) {
        if (objectKey == null) {
            return "";
        }
        int slash = objectKey.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String directory = objectKey.substring(0, slash + 1);
        if (basePrefix != null && !basePrefix.isEmpty() && directory.startsWith(basePrefix)) {
            directory = directory.substring(basePrefix.length());
        }
        return directory.replaceAll("^/+", "");
    }

    /** 当天目录前缀：{路径前缀}/{yyyy}/{MM}/{dd}/ —— 分片计数与对象键都基于它 */
    public static String dayDirectory(String pathPrefix) {
        String prefix = pathPrefix == null ? "" : pathPrefix;
        return prefix + LocalDate.now().format(DATE_FORMAT) + "/";
    }

    /** 转义 LIKE 通配符，供按对象键前缀统计使用（MySQL / H2 默认转义符都是反斜杠） */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static String fileName(String objectKey) {
        if (objectKey == null) {
            return "";
        }
        int index = objectKey.lastIndexOf('/');
        return index < 0 ? objectKey : objectKey.substring(index + 1);
    }

    /** 拼接公开访问地址，避免出现双斜杠 */
    public static String joinUrl(String base, String path) {
        if (base == null || base.isBlank()) {
            return path;
        }
        String left = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String right = path.startsWith("/") ? path.substring(1) : path;
        return left + "/" + right;
    }
}
