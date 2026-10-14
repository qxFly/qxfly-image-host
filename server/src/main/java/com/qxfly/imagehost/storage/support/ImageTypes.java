package com.qxfly.imagehost.storage.support;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 图片扩展名与 MIME 判定，供上传校验与"从存储导入"共用。
 */
public final class ImageTypes {

    private static final Set<String> EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico", "avif", "tiff");

    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("avif", "image/avif"),
            Map.entry("tiff", "image/tiff"));

    private ImageTypes() {
    }

    /** 按扩展名判断是否为图片 */
    public static boolean isImage(String path) {
        return EXTENSIONS.contains(ObjectKeys.extension(path));
    }

    public static String mimeOf(String path) {
        return MIME_TYPES.getOrDefault(ObjectKeys.extension(path), "application/octet-stream");
    }

    public static Set<String> extensions() {
        return EXTENSIONS;
    }

    public static String normalize(String extension) {
        return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    }
}
