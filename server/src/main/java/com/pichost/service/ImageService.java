package com.pichost.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pichost.common.BizException;
import com.pichost.common.PageResult;
import com.pichost.config.AppProperties;
import com.pichost.dto.Base64UploadRequest;
import com.pichost.dto.DirectoryNodeVO;
import com.pichost.dto.DirectoryTreeVO;
import com.pichost.dto.ImageVO;
import com.pichost.dto.NameValue;
import com.pichost.dto.StatsVO;
import com.pichost.entity.ImageEntity;
import com.pichost.entity.StorageConfigEntity;
import com.pichost.mapper.ImageMapper;
import com.pichost.storage.StorageObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageServiceFactory;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.StorageType;
import com.pichost.storage.UploadResult;
import com.pichost.storage.support.ObjectKeys;

import static com.pichost.storage.support.ObjectKeys.escapeLike;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 图片上传 / 查询 / 删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    /** 目录筛选的保留值：只看根目录下直接存放的文件 */
    public static final String ROOT_DIRECTORY = "__root__";

    /** 搜索模式：精准匹配（文件名或对象键完全相等） */
    public static final String MATCH_EXACT = "exact";

    /** 搜索模式：前缀匹配（目录路径 / 名称前缀） */
    public static final String MATCH_PREFIX = "prefix";

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

    private final ImageMapper imageMapper;
    private final StorageConfigService storageConfigService;
    private final StorageServiceFactory storageServiceFactory;
    private final AppProperties appProperties;
    private final ShardAllocator shardAllocator;

    @Transactional
    public ImageVO upload(Long userId, MultipartFile file, Long storageConfigId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        try {
            String originalName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "image";
            return store(userId, file.getBytes(), originalName, file.getContentType(), storageConfigId);
        } catch (IOException e) {
            throw new BizException("读取上传文件失败: " + e.getMessage());
        }
    }

    @Transactional
    public List<ImageVO> uploadBatch(Long userId, MultipartFile[] files, Long storageConfigId) {
        if (files == null || files.length == 0) {
            throw new BizException("请选择要上传的图片");
        }
        List<ImageVO> result = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            result.add(upload(userId, file, storageConfigId));
        }
        return result;
    }

    @Transactional
    public ImageVO uploadBase64(Long userId, Base64UploadRequest request) {
        String raw = request.getContent();
        String contentType = null;
        if (raw.startsWith("data:")) {
            int comma = raw.indexOf(',');
            if (comma < 0) {
                throw new BizException("data URI 格式不正确");
            }
            String header = raw.substring("data:".length(), comma);
            contentType = header.split(";")[0];
            raw = raw.substring(comma + 1);
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(raw.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new BizException("Base64 内容解析失败");
        }
        String fileName = StringUtils.hasText(request.getFileName()) ? request.getFileName() : "image.png";
        return store(userId, content, fileName, contentType, request.getStorageConfigId());
    }

    private ImageVO store(Long userId, byte[] content, String originalName, String contentType, Long storageConfigId) {
        if (content == null || content.length == 0) {
            throw new BizException("上传内容为空");
        }
        AppProperties.Upload uploadConfig = appProperties.getUpload();
        if (content.length > uploadConfig.getMaxSize()) {
            throw new BizException("文件大小超过限制，最大 " + (uploadConfig.getMaxSize() / 1024 / 1024) + "MB");
        }

        String extension = ObjectKeys.extension(originalName);
        if (extension.isEmpty() || !uploadConfig.getAllowedExtensions().contains(extension.toLowerCase(Locale.ROOT))) {
            throw new BizException("不支持的图片格式: " + (extension.isEmpty() ? originalName : extension)
                    + "，允许的格式: " + String.join(" / ", uploadConfig.getAllowedExtensions()));
        }
        String mimeType = resolveMimeType(contentType, extension);
        String hash = sha256(content);

        // 秒传：同一用户上传相同内容时直接复用
        if (appProperties.isDedupeEnabled()) {
            List<ImageEntity> same = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                    .eq(ImageEntity::getUserId, userId)
                    .eq(ImageEntity::getHash, hash)
                    .orderByDesc(ImageEntity::getId)
                    .last("LIMIT 1"));
            if (!same.isEmpty()) {
                log.info("命中秒传: {} ({} bytes)", originalName, content.length);
                return toVO(same.get(0), storageConfigService.nameMap(userId));
            }
        }

        StorageConfigEntity config = storageConfigService.resolveConfig(userId, storageConfigId);
        StorageSetting setting = storageConfigService.toSetting(config);
        StorageService storageService = storageServiceFactory.get(setting.type());

        String objectKey = buildObjectKey(userId, config, setting, originalName);
        UploadResult result = storageService.upload(setting,
                new StorageObject(objectKey, originalName, mimeType, content));

        ImageEntity entity = new ImageEntity();
        entity.setUserId(userId);
        entity.setStorageConfigId(config.getId());
        entity.setStorageType(config.getType());
        entity.setOriginalName(originalName);
        entity.setObjectKey(result.objectKey());
        entity.setUrl(result.url());
        entity.setThumbUrl(result.thumbUrl());
        entity.setMimeType(mimeType);
        entity.setSize((long) content.length);
        entity.setHash(hash);
        int[] dimension = readDimension(content);
        if (dimension != null) {
            entity.setWidth(dimension[0]);
            entity.setHeight(dimension[1]);
        }
        imageMapper.insert(entity);
        return toVO(entity, Map.of(config.getId(), config.getName()));
    }

    /**
     * 目录分片：同一存储后端当天的上传数每满 shardSize 个就换一个三位分片目录，
     * 避免单个目录堆积过多文件（GitHub 目录超过 1000 个文件在网页端/Contents API 上都会很难受）。
     */
    private String buildObjectKey(Long userId, StorageConfigEntity config, StorageSetting setting, String originalName) {
        int shard = shardAllocator.nextShard(userId, config.getId(), setting.pathPrefix());
        return ObjectKeys.build(setting.pathPrefix(), originalName, shard);
    }

    public PageResult<ImageVO> page(Long userId, long page, long size, String keyword,
                                   Long storageConfigId, String directory, String matchMode) {
        LambdaQueryWrapper<ImageEntity> wrapper = Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId)
                .eq(storageConfigId != null, ImageEntity::getStorageConfigId, storageConfigId)
                // __root__ 表示只看直接放在根目录的文件（对象键里没有斜杠）
                .notLike(ROOT_DIRECTORY.equals(directory), ImageEntity::getObjectKey, "%/%")
                // 其余目录筛选按前缀匹配，选中父目录会连同子目录一起显示（与目录树的 totalCount 一致）
                .likeRight(StringUtils.hasText(directory) && !ROOT_DIRECTORY.equals(directory),
                        ImageEntity::getObjectKey, ObjectKeys.escapeLike(directoryWithSlash(directory)))
                .orderByDesc(ImageEntity::getId);

        String value = normalizeKeyword(keyword);
        if (StringUtils.hasText(value)) {
            if (MATCH_EXACT.equalsIgnoreCase(matchMode)) {
                // 精准查询：完整对象键或完整文件名（适合直接粘贴一个对象键来定位）
                wrapper.and(w -> w.eq(ImageEntity::getObjectKey, value)
                        .or()
                        .eq(ImageEntity::getOriginalName, value));
            } else if (MATCH_PREFIX.equalsIgnoreCase(matchMode)) {
                // 目录 / 前缀查询：对象键或文件名以它开头（适合粘贴一个目录路径）
                String prefix = escapeLike(value);
                wrapper.and(w -> w.likeRight(ImageEntity::getObjectKey, prefix)
                        .or()
                        .likeRight(ImageEntity::getOriginalName, prefix));
            } else {
                // 模糊查询：文件名或对象键包含即可
                wrapper.and(w -> w.like(ImageEntity::getOriginalName, value)
                        .or()
                        .like(ImageEntity::getObjectKey, value));
            }
        }

        long safeSize = Math.min(Math.max(size, 1), 500);
        Page<ImageEntity> result = imageMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), wrapper);
        Map<Long, String> names = storageConfigService.nameMap(userId);
        List<ImageVO> records = result.getRecords().stream().map(e -> toVO(e, names)).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    public ImageVO get(Long userId, Long id) {
        ImageEntity entity = imageMapper.selectById(id);
        if (entity == null || !entity.getUserId().equals(userId)) {
            throw BizException.notFound("图片不存在");
        }
        return toVO(entity, storageConfigService.nameMap(userId));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ImageEntity entity = imageMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("图片不存在");
        }
        if (!entity.getUserId().equals(userId)) {
            throw BizException.forbidden("无权删除该图片");
        }
        try {
            StorageConfigEntity config = storageConfigService.requireOwned(userId, entity.getStorageConfigId());
            StorageService service = storageServiceFactory.get(storageConfigService.typeOf(config));
            if (service.supportsDelete() && StringUtils.hasText(entity.getObjectKey())) {
                service.delete(storageConfigService.toSetting(config), entity.getObjectKey());
            }
        } catch (Exception e) {
            // 存储后端删除失败不应阻塞本地记录清理
            log.warn("从存储后端删除对象失败，仅清理数据库记录: {}", e.getMessage());
        }
        imageMapper.deleteById(id);
    }

    public StatsVO stats(Long userId) {
        StatsVO stats = new StatsVO();
        stats.setTotalCount(imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId)));
        stats.setTodayCount(imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getUserId, userId)
                .ge(ImageEntity::getCreatedAt, LocalDate.now().atStartOfDay())));

        List<ImageEntity> sizes = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getSize)
                .eq(ImageEntity::getUserId, userId));
        stats.setTotalSize(sizes.stream().mapToLong(e -> e.getSize() == null ? 0L : e.getSize()).sum());
        stats.setStorageConfigCount(storageConfigService.nameMap(userId).size());

        stats.setTypeDistribution(distribution(userId));

        Map<Long, String> names = storageConfigService.nameMap(userId);
        stats.setRecent(imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                        .eq(ImageEntity::getUserId, userId)
                        .orderByDesc(ImageEntity::getId)
                        .last("LIMIT 12"))
                .stream().map(e -> toVO(e, names)).toList());
        return stats;
    }

    private String directoryWithSlash(String directory) {
        if (!StringUtils.hasText(directory)) {
            return "";
        }
        String value = directory.trim().replaceAll("^/+", "");
        return value.endsWith("/") ? value : value + "/";
    }

    /**
     * 目录树：按对象键聚合出目录层级与文件数。
     * 只查 object_key 一列在内存里建树，避免依赖 MySQL/H2 各自的字符串函数。
     */
    public DirectoryTreeVO directories(Long userId, Long storageConfigId) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getObjectKey)
                .eq(ImageEntity::getUserId, userId)
                .eq(storageConfigId != null, ImageEntity::getStorageConfigId, storageConfigId));

        DirNode root = new DirNode("");
        for (ImageEntity row : rows) {
            String key = row.getObjectKey();
            if (!StringUtils.hasText(key)) {
                continue;
            }
            int slash = key.lastIndexOf('/');
            if (slash < 0) {
                root.directCount++;
                continue;
            }
            DirNode current = root;
            for (String segment : key.substring(0, slash).split("/")) {
                if (segment.isEmpty()) {
                    continue;
                }
                current = current.children.computeIfAbsent(segment, DirNode::new);
            }
            current.directCount++;
        }

        DirectoryTreeVO tree = new DirectoryTreeVO();
        tree.setRootFileCount(root.directCount);
        tree.setTotal(rows.size());
        tree.setNodes(toDirectoryVO(root, ""));
        return tree;
    }

    private List<DirectoryNodeVO> toDirectoryVO(DirNode node, String parentPath) {
        List<DirectoryNodeVO> result = new ArrayList<>();
        for (DirNode child : node.children.values()) {
            String path = parentPath.isEmpty() ? child.name : parentPath + "/" + child.name;
            DirectoryNodeVO vo = new DirectoryNodeVO();
            vo.setPath(path);
            vo.setName(child.name);
            vo.setCount(child.directCount);
            vo.setTotalCount(child.rollUp());
            vo.setChildren(toDirectoryVO(child, path));
            result.add(vo);
        }
        return result;
    }

    /** 目录树节点（内部用） */
    private static final class DirNode {

        private final String name;
        private final Map<String, DirNode> children = new TreeMap<>();
        private int directCount;

        private DirNode(String name) {
            this.name = name;
        }

        /** 含子目录的总数 */
        private int rollUp() {
            int total = directCount;
            for (DirNode child : children.values()) {
                total += child.rollUp();
            }
            return total;
        }
    }

    private List<NameValue> distribution(Long userId) {
        List<Map<String, Object>> rows = imageMapper.selectMaps(new QueryWrapper<ImageEntity>()
                .select("storage_type", "count(*) as cnt")
                .eq("user_id", userId)
                .groupBy("storage_type"));
        List<NameValue> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String type = stringValue(row, "storage_type");
            long count = longValue(row, "cnt");
            String label = type;
            try {
                label = StorageType.of(type).getLabel();
            } catch (RuntimeException ignored) {
                // 历史数据里的未知类型，直接展示原始值
            }
            result.add(new NameValue(type, label, count));
        }
        return result;
    }

    private static String stringValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? null : String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private static long longValue(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    private ImageVO toVO(ImageEntity entity, Map<Long, String> configNames) {
        ImageVO vo = new ImageVO();
        vo.setId(entity.getId());
        vo.setOriginalName(entity.getOriginalName());
        vo.setObjectKey(entity.getObjectKey());
        String directory = directoryOf(entity.getObjectKey());
        vo.setDirectory(directory);
        vo.setDepth(directory.isEmpty() ? 0 : directory.split("/").length);
        vo.setUrl(entity.getUrl());
        vo.setThumbUrl(entity.getThumbUrl());
        vo.setMimeType(entity.getMimeType());
        vo.setSize(entity.getSize());
        vo.setWidth(entity.getWidth());
        vo.setHeight(entity.getHeight());
        vo.setStorageType(entity.getStorageType());
        vo.setStorageConfigId(entity.getStorageConfigId());
        vo.setStorageConfigName(configNames.get(entity.getStorageConfigId()));
        vo.setCreatedAt(entity.getCreatedAt());
        try {
            vo.setStorageTypeLabel(StorageType.of(entity.getStorageType()).getLabel());
        } catch (RuntimeException e) {
            vo.setStorageTypeLabel(entity.getStorageType());
        }
        return vo;
    }

    /**
     * 搜索关键字归一化。
     * 对象键是相对路径，永远不以 / 开头，但用户经常整段粘贴带前导斜杠的路径
     * （例如 /api/images/2026/10/12/002），不处理的话连模糊查询都匹配不到。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String value = keyword.trim();
        return value.replaceAll("^/+", "");
    }

    private static String directoryOf(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return "";
        }
        int slash = objectKey.lastIndexOf('/');
        return slash < 0 ? "" : objectKey.substring(0, slash);
    }

    private String resolveMimeType(String contentType, String extension) {
        if (StringUtils.hasText(contentType) && contentType.startsWith("image/")) {
            return contentType;
        }
        return MIME_TYPES.getOrDefault(extension.toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读取图片宽高，失败（如 SVG）返回 null */
    private int[] readDimension(byte[] content) {
        try (ImageInputStream inputStream = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            if (inputStream == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(inputStream);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
