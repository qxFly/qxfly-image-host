package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.security.AuthUser;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 图库图片下载。
 * <p>
 * 两条路径都走服务端代理：浏览器的 &lt;a href&gt; 带不上 JWT，而私有仓库 / 私有桶的直链
 * 也拿不到内容，所以统一由后端用 {@link ImageContentFetcher} 取回原始字节再回传给浏览器。
 * <p>
 * 批量下载是「边取边写」的 zip 流：内存里最多只放一张图，几百张也不会把服务端撑爆；
 * 单张失败只写进包内的失败清单，不影响其余图片。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageDownloadService {

    /** 一次批量下载最多打包多少张，避免一个请求把服务端和浏览器都拖垮 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 批量下载时，失败的文件清单（zip 里的最后一个条目） */
    private static final String FAILED_LIST_ENTRY = "下载失败清单.txt";

    private static final String FALLBACK_CONTENT_TYPE = "application/octet-stream";

    /** 单个 zip 条目名的最大长度，过长的文件名在部分解压工具里会出问题 */
    private static final int MAX_ENTRY_NAME_LENGTH = 120;

    private final ImageMapper imageMapper;
    private final StorageConfigService storageConfigService;
    private final ImageContentFetcher contentFetcher;
    private final UserService userService;

    /**
     * 单张图片的原始内容。
     *
     * @param fileName    给用户保存时用的文件名（已去掉路径分隔符）
     * @param contentType MIME 类型
     * @param content     文件字节
     */
    public record ImageFile(String fileName, String contentType, byte[] content) {
    }

    /** 取单张图片的原始内容；不存在或不在可见范围内时抛 404 */
    public ImageFile download(AuthUser user, Long id) {
        ImageEntity image = requireVisible(user, id);
        // 按「图片所属账号」的存储配置取内容：普通用户下载系统图库里的图片时，
        // 用的是超级管理员的配置，而不是自己的（只读账号自己没有配置）
        byte[] content = contentFetcher.fetch(image, settingOf(image.getUserId(), image.getStorageConfigId()));
        return new ImageFile(safeName(image.getOriginalName()), contentTypeOf(image), content);
    }

    /**
     * 取出要打包的图片，顺序与请求一致。
     * 有任何一张不存在 / 不属于当前用户就直接报错，不让用户拿到一个「悄悄少了几张」的压缩包。
     */
    public List<ImageEntity> resolveBatch(AuthUser user, List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new BizException("请先选择要下载的图片");
        }
        List<Long> distinct = imageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new BizException("请先选择要下载的图片");
        }
        if (distinct.size() > MAX_BATCH_SIZE) {
            throw new BizException("一次最多批量下载 " + MAX_BATCH_SIZE + " 张，当前选了 " + distinct.size() + " 张");
        }

        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                // 回收站里的图片不参与下载
                .eq(ImageEntity::getDeleted, 0)
                .in(ImageEntity::getId, distinct));
        Map<Long, ImageEntity> byId = new HashMap<>(rows.size());
        rows.forEach(row -> byId.put(row.getId(), row));

        List<ImageEntity> ordered = new ArrayList<>(distinct.size());
        int missing = 0;
        for (Long id : distinct) {
            ImageEntity image = byId.get(id);
            if (image == null) {
                missing++;
            } else {
                ordered.add(image);
            }
        }
        if (missing > 0) {
            throw BizException.notFound("有 " + missing + " 张图片不存在或无权访问，请刷新后重试");
        }
        return ordered;
    }

    /**
     * 把图片逐个写进 zip。
     *
     * @return 成功写入的文件数
     */
    public int writeZip(List<ImageEntity> images, OutputStream output) throws IOException {
        Set<String> usedNames = new HashSet<>();
        List<String> failures = new ArrayList<>();
        Map<Long, StorageSetting> settings = new HashMap<>();
        int written = 0;

        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            // 图片本身已经是压缩格式，再压一遍只是白耗 CPU
            zip.setLevel(Deflater.BEST_SPEED);
            for (ImageEntity image : images) {
                try {
                    StorageSetting setting = settings.get(image.getStorageConfigId());
                    if (setting == null) {
                        setting = settingOf(image.getUserId(), image.getStorageConfigId());
                        settings.put(image.getStorageConfigId(), setting);
                    }
                    byte[] content = contentFetcher.fetch(image, setting);
                    zip.putNextEntry(new ZipEntry(uniqueName(safeName(image.getOriginalName()), usedNames)));
                    zip.write(content);
                    zip.closeEntry();
                    written++;
                } catch (Exception e) {
                    // 一张坏图不该毁掉整包，记进清单继续
                    log.warn("批量下载跳过 id={} key={}: {}", image.getId(), image.getObjectKey(), e.getMessage());
                    failures.add(safeName(image.getOriginalName()) + "（" + e.getMessage() + "）");
                }
            }
            if (!failures.isEmpty()) {
                zip.putNextEntry(new ZipEntry(FAILED_LIST_ENTRY));
                zip.write(("本次有 " + failures.size() + " 张图片下载失败，已跳过：\n\n"
                        + String.join("\n", failures) + "\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        log.info("批量下载完成：成功 {} 张，失败 {} 张，共 {} 张", written, failures.size(), images.size());
        return written;
    }

    private ImageEntity requireVisible(AuthUser user, Long id) {
        ImageEntity image = imageMapper.selectById(id);
        if (image == null
                || (image.getDeleted() != null && image.getDeleted() != 0)
                || !userService.visibleOwnerIds(user).contains(image.getUserId())) {
            throw BizException.notFound("图片不存在");
        }
        return image;
    }

    private StorageSetting settingOf(Long userId, Long storageConfigId) {
        return storageConfigService.toSetting(storageConfigService.requireOwned(userId, storageConfigId));
    }

    private String contentTypeOf(ImageEntity image) {
        if (StringUtils.hasText(image.getMimeType())) {
            return image.getMimeType();
        }
        String extension = ObjectKeys.extension(image.getOriginalName());
        if (extension.isEmpty()) {
            return FALLBACK_CONTENT_TYPE;
        }
        return "image/" + ("jpg".equals(extension) ? "jpeg" : extension);
    }

    /**
     * 压缩包条目名只保留文件名本身：去掉路径分隔符，免得解压时写到目标目录外面（zip slip）。
     */
    private static String safeName(String originalName) {
        String name = StringUtils.hasText(originalName) ? originalName : "image";
        // 路径分隔符、控制字符和 Windows 非法字符一律替换掉；
        // 只允许文件名本身出现在压缩包里，避免解压时写到目标目录外面（zip slip）
        StringBuilder builder = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            boolean illegal = c == '\\' || c == '/' || c < 0x20 || "<>:\"|?*".indexOf(c) >= 0;
            builder.append(illegal ? '_' : c);
        }
        name = builder.toString().trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > MAX_ENTRY_NAME_LENGTH) {
            int dot = name.lastIndexOf('.');
            String extension = (dot > 0 && name.length() - dot <= 10) ? name.substring(dot) : "";
            name = name.substring(0, MAX_ENTRY_NAME_LENGTH - extension.length()) + extension;
        }
        return name.isEmpty() ? "image" : name;
    }

    /** 同名文件追加 -1、-2 后缀，保证压缩包里不会互相覆盖 */
    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        int index = 1;
        String candidate = base + "-" + index + extension;
        while (!used.add(candidate)) {
            index++;
            candidate = base + "-" + index + extension;
        }
        return candidate;
    }
}
