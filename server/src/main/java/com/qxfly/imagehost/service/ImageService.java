package com.qxfly.imagehost.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qxfly.imagehost.common.BizException;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.config.AppProperties;
import com.qxfly.imagehost.dto.Base64UploadRequest;
import com.qxfly.imagehost.dto.DirectoryNodeVO;
import com.qxfly.imagehost.dto.DirectoryTreeVO;
import com.qxfly.imagehost.dto.CdnVO;
import com.qxfly.imagehost.dto.ImageLinkVO;
import com.qxfly.imagehost.dto.ImageVO;
import com.qxfly.imagehost.dto.NameValue;
import com.qxfly.imagehost.dto.StatsVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.entity.ShareLinkEntity;
import com.qxfly.imagehost.entity.StorageConfigEntity;
import com.qxfly.imagehost.mapper.ImageMapper;
import com.qxfly.imagehost.security.AuthUser;
import com.qxfly.imagehost.storage.StorageObject;
import com.qxfly.imagehost.storage.StorageService;
import com.qxfly.imagehost.storage.StorageServiceFactory;
import com.qxfly.imagehost.storage.StorageSetting;
import com.qxfly.imagehost.storage.StorageType;
import com.qxfly.imagehost.storage.impl.LocalStorageService;
import com.qxfly.imagehost.storage.UploadResult;
import com.qxfly.imagehost.storage.support.ObjectKeys;

import static com.qxfly.imagehost.storage.support.ObjectKeys.escapeLike;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** image_file.deleted：0 = 正常（在用），1 = 已在回收站 */
    private static final int LIVE = 0;
    private static final int DELETED = 1;

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
    private final UserService userService;
    private final TrashMover trashMover;
    private final StorageCdnService storageCdnService;
    private final AppSettingService appSettingService;
    private final AuditService auditService;
    private final ThumbnailService thumbnailService;
    private final RequestBase requestBase;
    private final com.qxfly.imagehost.mapper.ShareLinkMapper shareLinkMapper;

    @Transactional
    public ImageVO upload(Long userId, MultipartFile file, Long storageConfigId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        // 先判断大小再读内容：超大文件不该先整个读进内存（上限可在「系统设置」里改，支持无上限）
        long limit = appSettingService.maxUploadSize();
        if (limit > 0 && file.getSize() > limit) {
            throw new BizException("文件大小超过限制：" + (StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename() : "该文件") + " 为 " + AppSettingService.readable(file.getSize())
                    + "，上限 " + AppSettingService.readable(limit));
        }
        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "image";
        // 先落到临时文件，之后整条链路按流处理：内存占用与文件大小无关，
        // 所以「单张无上限」时才不会在 file.getBytes() 上把堆撑爆
        Path temp = null;
        try {
            temp = Files.createTempFile(uploadTempDir(), "qxfly-upload-", ".tmp");
            file.transferTo(temp);
            return store(userId, PendingContent.ofFile(temp, file.getSize()),
                    originalName, file.getContentType(), storageConfigId);
        } catch (IOException e) {
            throw new BizException("保存上传文件失败: " + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("删除上传临时文件失败: {}", temp);
                }
            }
        }
    }

    /** 上传临时目录（app.upload.temp-dir，留空用系统临时目录） */
    private Path uploadTempDir() throws IOException {
        String configured = appProperties.getUpload().getTempDir();
        Path dir = StringUtils.hasText(configured)
                ? Paths.get(configured)
                : Paths.get(System.getProperty("java.io.tmpdir"), "qxfly-image-host");
        Files.createDirectories(dir);
        return dir;
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
        return store(userId, PendingContent.ofBytes(content), fileName, contentType, request.getStorageConfigId());
    }

    private ImageVO store(Long userId, PendingContent content, String originalName, String contentType,
                          Long storageConfigId) {
        long size = content.length();
        if (size <= 0) {
            throw new BizException("上传内容为空");
        }
        AppProperties.Upload uploadConfig = appProperties.getUpload();
        long sizeLimit = appSettingService.maxUploadSize();
        if (sizeLimit > 0 && size > sizeLimit) {
            throw new BizException("文件大小超过限制，最大 " + AppSettingService.readable(sizeLimit));
        }

        String extension = ObjectKeys.extension(originalName);
        if (extension.isEmpty() || !uploadConfig.getAllowedExtensions().contains(extension.toLowerCase(Locale.ROOT))) {
            throw new BizException("不支持的图片格式: " + (extension.isEmpty() ? originalName : extension)
                    + "，允许的格式: " + String.join(" / ", uploadConfig.getAllowedExtensions()));
        }
        String mimeType = resolveMimeType(contentType, extension);
        String hash = content.sha256();

        // 秒传：同一用户上传相同内容时直接复用
        if (appProperties.isDedupeEnabled()) {
            List<ImageEntity> same = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                    .eq(ImageEntity::getUserId, userId)
                    .eq(ImageEntity::getHash, hash)
                    // 回收站里的不算，否则上传相同内容会直接返回一张"看不见"的图
                    .eq(ImageEntity::getDeleted, LIVE)
                    .orderByDesc(ImageEntity::getId)
                    .last("LIMIT 1"));
            if (!same.isEmpty()) {
                log.info("命中秒传: {} ({} bytes)", originalName, size);
                ImageEntity hit = same.get(0);
                return toVO(hit, storageConfigService.mapByOwners(List.of(hit.getUserId())));
            }
        }

        StorageConfigEntity config = storageConfigService.resolveConfig(userId, storageConfigId);
        StorageSetting setting = storageConfigService.toSetting(config);
        StorageService storageService = storageServiceFactory.get(setting.type());

        String objectKey = buildObjectKey(userId, config, setting, originalName);
        // 文件来源的对象会走流式上传（S3 / 本地 / Release 附件），内存里不会出现整份文件
        UploadResult result = storageService.upload(setting, content.toObject(objectKey, originalName, mimeType));

        ImageEntity entity = new ImageEntity();
        entity.setUserId(userId);
        entity.setStorageConfigId(config.getId());
        entity.setStorageType(config.getType());
        entity.setOriginalName(originalName);
        entity.setObjectKey(result.objectKey());
        entity.setUrl(result.url());
        entity.setThumbUrl(result.thumbUrl());
        entity.setMimeType(mimeType);
        entity.setSize(size);
        entity.setHash(hash);
        entity.setDeleted(LIVE);
        int[] dimension = content.dimension();
        if (dimension != null) {
            entity.setWidth(dimension[0]);
            entity.setHeight(dimension[1]);
        }
        imageMapper.insert(entity);
        auditService.log(AuditService.Actions.UPLOAD, "IMAGE", entity.getId(),
                originalName + " -> " + config.getName() + "（" + AppSettingService.readable(size) + "）");
        return toVO(entity, Map.of(config.getId(), config));
    }

    /**
     * 目录分片：同一存储后端当天的上传数每满 shardSize 个就换一个三位分片目录，
     * 避免单个目录堆积过多文件（GitHub 目录超过 1000 个文件在网页端/Contents API 上都会很难受）。
     */
    private String buildObjectKey(Long userId, StorageConfigEntity config, StorageSetting setting, String originalName) {
        int shard = shardAllocator.nextShard(userId, config.getId(), setting.pathPrefix());
        return ObjectKeys.build(setting.pathPrefix(), originalName, shard);
    }

    public PageResult<ImageVO> page(AuthUser user, com.qxfly.imagehost.dto.ImageQueryDTO query) {
        String keyword = query.getKeyword();
        String matchMode = query.getMatchMode();
        String directory = query.getDirectory();
        Long storageConfigId = query.getStorageConfigId();
        // 超级管理员看自己的图库，普通用户看超级管理员的图库（只读）
        LambdaQueryWrapper<ImageEntity> wrapper = Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, LIVE)
                .eq(storageConfigId != null, ImageEntity::getStorageConfigId, storageConfigId)
                // __root__ 表示只看直接放在根目录的文件（对象键里没有斜杠）
                .notLike(ROOT_DIRECTORY.equals(directory), ImageEntity::getObjectKey, "%/%")
                // 其余目录筛选按前缀匹配，选中父目录会连同子目录一起显示（与目录树的 totalCount 一致）
                .likeRight(StringUtils.hasText(directory) && !ROOT_DIRECTORY.equals(directory),
                        ImageEntity::getObjectKey, ObjectKeys.escapeLike(directoryWithSlash(directory)))
                // 体积 / 日期 / 尺寸区间（未填的维度不参与过滤）
                .ge(query.getMinSize() != null, ImageEntity::getSize, query.getMinSize())
                .le(query.getMaxSize() != null, ImageEntity::getSize, query.getMaxSize())
                .ge(query.createdAtFrom() != null, ImageEntity::getCreatedAt, query.createdAtFrom())
                .le(query.createdAtTo() != null, ImageEntity::getCreatedAt, query.createdAtTo())
                .ge(query.getMinWidth() != null, ImageEntity::getWidth, query.getMinWidth())
                .le(query.getMaxWidth() != null, ImageEntity::getWidth, query.getMaxWidth())
                .ge(query.getMinHeight() != null, ImageEntity::getHeight, query.getMinHeight())
                .le(query.getMaxHeight() != null, ImageEntity::getHeight, query.getMaxHeight())
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

        long safeSize = Math.min(Math.max(query.getSize(), 1), 500);
        Page<ImageEntity> result = imageMapper.selectPage(
                new Page<>(Math.max(query.getPage(), 1), safeSize), wrapper);
        Map<Long, StorageConfigEntity> configs = configsOf(user);
        List<ImageVO> records = result.getRecords().stream().map(e -> toVO(e, configs)).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    public ImageVO get(AuthUser user, Long id) {
        ImageEntity entity = imageMapper.selectById(id);
        if (entity == null || isDeleted(entity) || !userService.visibleOwnerIds(user).contains(entity.getUserId())) {
            throw BizException.notFound("图片不存在");
        }
        return toVO(entity, configsOf(user));
    }

    /** 取该存储配置下最近一张图片的「对象键 + 地址」，用于 CDN 测速拼示例地址 */
    public String[] sampleOf(Long storageConfigId) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .eq(ImageEntity::getStorageConfigId, storageConfigId)
                .eq(ImageEntity::getDeleted, LIVE)
                .orderByDesc(ImageEntity::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : new String[]{rows.get(0).getObjectKey(), rows.get(0).getUrl()};
    }

    /**
     * 一张图片可用的全部链接：原始地址 + 各条 CDN + 服务端代理下载。
     * 供前端「选择复制哪种链接」的选择框使用（连不上、延时多少由前端自己测）。
     */
    public List<ImageLinkVO> links(AuthUser user, Long id) {
        ImageEntity entity = imageMapper.selectById(id);
        if (entity == null || isDeleted(entity) || !userService.visibleOwnerIds(user).contains(entity.getUserId())) {
            throw BizException.notFound("图片不存在");
        }
        StorageConfigEntity config = storageConfigService.mapByOwners(List.of(entity.getUserId()))
                .get(entity.getStorageConfigId());

        List<ImageLinkVO> links = new ArrayList<>();
        links.add(toLink(StorageCdnService.ORIGIN_KEY, "原始地址（存储后端直链）", entity.getUrl(), true, entity, config));
        if (config != null) {
            for (CdnVO cdn : storageCdnService.list(config, entity.getObjectKey(), entity.getUrl())) {
                if (StorageCdnService.ORIGIN_KEY.equals(cdn.getKey()) || !cdn.isEnabled()) {
                    continue;
                }
                links.add(toLink(cdn.getKey(), cdn.getName(), cdn.getExample(), cdn.isBuiltin(), entity, config));
            }
        }

        // 服务端代理下载：私有仓库 / 私有桶，或者 CDN 都连不上时的兜底（需要登录）
        ImageLinkVO proxy = new ImageLinkVO();
        proxy.setKey(StorageCdnService.PROXY_KEY);
        proxy.setName("原站代理下载（需登录，支持私有存储）");
        proxy.setProxy(true);
        proxy.setBuiltin(true);
        links.add(proxy);
        return links;
    }

    private ImageLinkVO toLink(String key, String name, String url, boolean builtin,
                               ImageEntity entity, StorageConfigEntity config) {
        ImageLinkVO vo = new ImageLinkVO();
        vo.setKey(key);
        vo.setName(name);
        vo.setUrl(url);
        vo.setBuiltin(builtin);
        vo.setOrigin(StorageCdnService.ORIGIN_KEY.equals(key));
        vo.setMarkdown("![" + (entity.getOriginalName() == null ? "" : entity.getOriginalName()) + "](" + url + ")");
        vo.setPreviewDefault(key.equals(storageCdnService.defaultKey(config, "preview")));
        vo.setDownloadDefault(key.equals(storageCdnService.defaultKey(config, "download")));
        vo.setCopyDefault(key.equals(storageCdnService.defaultKey(config, "copy")));
        return vo;
    }

    // ================================================================ 回收站

    /**
     * 删除 = 移入回收站（逻辑删除）。
     * <p>
     * 数据库打标记之外，仓库里的文件也会被搬进回收站目录（默认 {@code _trash/}，
     * 走 GitHub 的引用式搬移，不产生流量），恢复时再搬回原位置；
     * 不支持同仓库搬移的存储后端退化为「只打标记、文件原地保留」。
     */
    @Transactional
    public void moveToTrash(Long userId, Long id) {
        ImageEntity entity = imageMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("图片不存在");
        }
        if (!entity.getUserId().equals(userId)) {
            throw BizException.forbidden("无权删除该图片");
        }
        if (isDeleted(entity)) {
            // 已经在回收站里，重复删除保持幂等（不要刷新删除时间）
            return;
        }
        // 先搬文件再落库：避免数据库里记着一个并不存在的回收站位置
        entity.setTrashKey(moveRemoteToTrash(entity));
        entity.setDeleted(DELETED);
        entity.setDeletedAt(LocalDateTime.now());
        imageMapper.updateById(entity);
        log.info("图片已移入回收站: id={} key={} trashKey={}", entity.getId(), entity.getObjectKey(), entity.getTrashKey());
        auditService.log(AuditService.Actions.TRASH_MOVE, "IMAGE", entity.getId(),
                "移入回收站 " + entity.getOriginalName());
    }

    /**
     * 批量删除 = 批量移入回收站。返回移入回收站的张数。
     * <p>
     * 与单张删除同一套语义（先搬文件再打标记），只是先一次查全再逐张处理。
     * 只要有一个 id 找不到（不存在 / 已在回收站 / 不可见）就整体报错，
     * 不做「删了一半」的部分成功 —— 前端刷新列表后重试即可。
     */
    @Transactional
    public int batchMoveToTrash(AuthUser user, List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new BizException("请先选择要删除的图片");
        }
        List<Long> distinct = imageIds.stream().filter(Objects::nonNull).distinct().toList();
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, LIVE)
                .in(ImageEntity::getId, distinct));
        if (rows.size() < distinct.size()) {
            throw BizException.notFound("有 " + (distinct.size() - rows.size())
                    + " 张图片不存在或已被删除，请刷新后重试");
        }
        int deleted = 0;
        for (ImageEntity entity : rows) {
            // 先搬文件再落库：避免数据库里记着一个并不存在的回收站位置
            entity.setTrashKey(moveRemoteToTrash(entity));
            entity.setDeleted(DELETED);
            entity.setDeletedAt(LocalDateTime.now());
            imageMapper.updateById(entity);
            deleted++;
        }
        log.info("批量移入回收站 {} 张图片", deleted);
        return deleted;
    }

    /**
     * 按给定 id 顺序取图片（分享相册用）。
     * <p>
     * 不做归属校验 —— 调用方（分享链接）自己已经确认过这些图片是允许被看到的。
     */
    public List<ImageVO> listByIds(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return List.of();
        }
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getId, imageIds)
                .eq(ImageEntity::getDeleted, LIVE));
        Map<Long, ImageEntity> byId = new LinkedHashMap<>();
        List<Long> ownerIds = new ArrayList<>();
        for (ImageEntity row : rows) {
            byId.put(row.getId(), row);
            if (!ownerIds.contains(row.getUserId())) {
                ownerIds.add(row.getUserId());
            }
        }
        Map<Long, StorageConfigEntity> configs = storageConfigService.mapByOwners(ownerIds);
        List<ImageVO> result = new ArrayList<>(imageIds.size());
        for (Long id : imageIds) {
            ImageEntity entity = byId.get(id);
            if (entity != null) {
                result.add(toVO(entity, configs));
            }
        }
        return result;
    }

    /**
     * 导出图片元数据清单（数据安全兜底：图片在云端、清单在库里，库出问题时靠它恢复）。
     * <p>
     * 只导出元数据，不含图片内容；JSON 为数组、CSV 带 BOM（Excel 打开中文不乱码）。
     */
    public byte[] exportManifest(AuthUser user, String format) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, ownerIds)
                .orderByAsc(ImageEntity::getId)
                .last("LIMIT 100000"));
        if ("json".equalsIgnoreCase(format)) {
            List<Map<String, Object>> items = new ArrayList<>(rows.size());
            for (ImageEntity e : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", e.getId());
                item.put("userId", e.getUserId());
                item.put("storageConfigId", e.getStorageConfigId());
                item.put("storageType", e.getStorageType());
                item.put("originalName", e.getOriginalName());
                item.put("objectKey", e.getObjectKey());
                item.put("url", e.getUrl());
                item.put("thumbUrl", e.getThumbUrl());
                item.put("mimeType", e.getMimeType());
                item.put("size", e.getSize());
                item.put("hash", e.getHash());
                item.put("width", e.getWidth());
                item.put("height", e.getHeight());
                item.put("deleted", e.getDeleted());
                item.put("trashKey", e.getTrashKey());
                item.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
                items.add(item);
            }
            StringBuilder json = new StringBuilder("{\"items\":");
            json.append(com.qxfly.imagehost.storage.support.Json.write(items)).append('}');
            return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("id,userId,storageConfigId,storageType,originalName,objectKey,url,thumbUrl,mimeType,size,hash,width,height,deleted,trashKey,createdAt\n");
        for (ImageEntity e : rows) {
            csv.append(e.getId()).append(',')
                    .append(e.getUserId()).append(',')
                    .append(e.getStorageConfigId()).append(',')
                    .append(csvCell(e.getStorageType())).append(',')
                    .append(csvCell(e.getOriginalName())).append(',')
                    .append(csvCell(e.getObjectKey())).append(',')
                    .append(csvCell(e.getUrl())).append(',')
                    .append(csvCell(e.getThumbUrl())).append(',')
                    .append(csvCell(e.getMimeType())).append(',')
                    .append(e.getSize() == null ? 0 : e.getSize()).append(',')
                    .append(csvCell(e.getHash())).append(',')
                    .append(e.getWidth() == null ? "" : e.getWidth()).append(',')
                    .append(e.getHeight() == null ? "" : e.getHeight()).append(',')
                    .append(e.getDeleted() == null ? 0 : e.getDeleted()).append(',')
                    .append(csvCell(e.getTrashKey())).append(',')
                    .append(csvCell(e.getCreatedAt() == null ? null : e.getCreatedAt().toString())).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r", " ").replace("\n", " ");
        if (text.contains(",") || text.contains("\"")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * 按导出的清单回灌元数据：只补库里没有的记录，不碰云端文件。
     * 去重键 = objectKey（同一对象键只留一条）；被删掉的记录会一并恢复成"在用"。
     *
     * @return 插入与跳过的数量
     */
    @Transactional
    public Map<String, Object> importManifest(AuthUser user, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BizException("清单为空：请先上传导出的 csv/json 文件");
        }
        if (rows.size() > 100000) {
            throw new BizException("清单最多 100000 行，当前 " + rows.size());
        }
        Long userId = user.id();
        List<Long> configIds = userService.visibleOwnerIds(user);
        // 没有任何可用存储配置时无法回灌（图片总要挂在某个后端上）
        List<com.qxfly.imagehost.dto.StorageConfigVO> configs = storageConfigService.list(userId);
        StorageConfigEntity fallback = configs.isEmpty()
                ? null : storageConfigService.requireOwned(userId, configs.get(0).getId());

        int inserted = 0;
        int skipped = 0;
        Set<String> seenKeys = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) {
            String objectKey = stringValueOf(row.get("objectKey"));
            if (!StringUtils.hasText(objectKey)) {
                skipped++;
                continue;
            }
            if (!seenKeys.add(objectKey)) {
                skipped++;
                continue;
            }
            // 库里已有同对象键的记录就跳过（幂等：重复导入不会产生重复行）
            Long exists = imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                    .in(ImageEntity::getUserId, configIds)
                    .eq(ImageEntity::getObjectKey, objectKey));
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            // CSV 回灌时字段都是字符串，JSON 回灌时可能是数字，这里统一做数值转换
            Long configId = toLong(row.get("storageConfigId"));
            boolean configOk = configId != null && configIds.contains(configId);
            if (!configOk && fallback == null) {
                skipped++;
                continue;
            }

            ImageEntity entity = new ImageEntity();
            entity.setUserId(userId);
            entity.setStorageConfigId(configOk ? configId : fallback.getId());
            entity.setStorageType(configOk ? stringValueOf(row.get("storageType")) : fallback.getType());
            entity.setOriginalName(StringUtils.hasText(stringValueOf(row.get("originalName")))
                    ? stringValueOf(row.get("originalName")) : ObjectKeys.fileName(objectKey));
            entity.setObjectKey(objectKey);
            entity.setUrl(StringUtils.hasText(stringValueOf(row.get("url"))) ? stringValueOf(row.get("url")) : "");
            entity.setThumbUrl(stringValueOf(row.get("thumbUrl")));
            entity.setMimeType(stringValueOf(row.get("mimeType")));
            Long size = toLong(row.get("size"));
            entity.setSize(size == null ? 0L : size);
            entity.setHash(stringValueOf(row.get("hash")));
            entity.setWidth(toInt(row.get("width")));
            entity.setHeight(toInt(row.get("height")));
            // 回灌的记录默认放回图库（回填的记录如果本就是回收站状态，会因文件可能已不在而留痕，这里统一按在用处理）
            entity.setDeleted(LIVE);
            entity.setTrashKey(null);
            imageMapper.insert(entity);
            inserted++;
        }
        auditService.log(AuditService.Actions.SETTINGS_UPDATE, "IMAGE", null,
                "回灌元数据清单：" + inserted + " 条插入、" + skipped + " 条跳过");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", inserted);
        result.put("skipped", skipped);
        result.put("total", rows.size());
        return result;
    }

    private static String stringValueOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer toInt(Object value) {
        Long parsed = toLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    /** 回收站列表：只列出逻辑删除的图片，最近删的在前 */
    public PageResult<ImageVO> pageTrash(AuthUser user, long page, long size, String keyword) {
        LambdaQueryWrapper<ImageEntity> wrapper = Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, DELETED)
                .orderByDesc(ImageEntity::getDeletedAt)
                .orderByDesc(ImageEntity::getId);

        String value = normalizeKeyword(keyword);
        if (StringUtils.hasText(value)) {
            wrapper.and(w -> w.like(ImageEntity::getOriginalName, value)
                    .or()
                    .like(ImageEntity::getObjectKey, value));
        }

        long safeSize = Math.min(Math.max(size, 1), 500);
        Page<ImageEntity> result = imageMapper.selectPage(new Page<>(Math.max(page, 1), safeSize), wrapper);
        Map<Long, StorageConfigEntity> configs = configsOf(user);
        List<ImageVO> records = result.getRecords().stream().map(e -> toVO(e, configs)).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    /**
     * 从回收站恢复。返回恢复的张数。
     * <p>
     * 先把云端文件都搬回原位置，全都成功了才改数据库，
     * 避免出现「数据库说恢复了、文件还躺在回收站目录」的错位状态。
     */
    @Transactional
    public int restore(AuthUser user, List<Long> imageIds) {
        List<ImageEntity> rows = requireTrashed(user, imageIds);

        List<String> failures = new ArrayList<>();
        for (ImageEntity row : rows) {
            try {
                restoreRemote(row);
            } catch (Exception e) {
                log.warn("恢复云端文件失败 id={} key={}: {}", row.getId(), row.getObjectKey(), e.getMessage());
                failures.add(row.getOriginalName() + "（" + e.getMessage() + "）");
            }
        }
        if (!failures.isEmpty()) {
            throw new BizException("有 " + failures.size() + " 张图片的云端文件没能搬回原位，本次未做恢复："
                    + String.join("；", failures));
        }

        int restored = 0;
        for (ImageEntity row : rows) {
            // 注意：updateById 默认会跳过 null 字段，deleted_at / trash_key 必须用 update wrapper 显式置空
            imageMapper.update(null, Wrappers.<ImageEntity>lambdaUpdate()
                    .eq(ImageEntity::getId, row.getId())
                    .set(ImageEntity::getDeleted, LIVE)
                    .set(ImageEntity::getDeletedAt, null)
                    .set(ImageEntity::getTrashKey, null));
            restored++;
        }
        log.info("从回收站恢复 {} 张图片", restored);
        auditService.log(AuditService.Actions.TRASH_RESTORE, "IMAGE", null,
                "从回收站恢复 " + restored + " 张图片");
        return restored;
    }

    /**
     * 彻底删除：先删云端对象，再删本地记录，不可恢复。
     * 云端删除失败只记警告 —— 否则会留下"本地没了、云端还在"的孤儿文件更难处理。
     */
    @Transactional
    public int purge(AuthUser user, List<Long> imageIds) {
        List<ImageEntity> rows = requireTrashed(user, imageIds);
        int purged = 0;
        for (ImageEntity row : rows) {
            deleteRemoteObject(row);
            imageMapper.deleteById(row.getId());
            purged++;
        }
        log.info("彻底删除 {} 张图片（含云端对象）", purged);
        auditService.log(AuditService.Actions.IMAGE_PURGE, "IMAGE", null,
                "彻底删除 " + purged + " 张图片（含云端对象）");
        return purged;
    }

    /** 清空回收站 */
    @Transactional
    public int clearTrash(AuthUser user) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, DELETED));
        int purged = 0;
        for (ImageEntity row : rows) {
            deleteRemoteObject(row);
            imageMapper.deleteById(row.getId());
            purged++;
        }
        log.info("清空回收站，彻底删除 {} 张图片", purged);
        auditService.log(AuditService.Actions.TRASH_CLEAR, "IMAGE", null,
                "清空回收站，彻底删除 " + purged + " 张图片");
        return purged;
    }

    /** 取出回收站里这些 id 对应的记录（必须在可见范围内、且确实处于删除状态） */
    private List<ImageEntity> requireTrashed(AuthUser user, List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new BizException("请先选择要操作的图片");
        }
        List<Long> distinct = imageIds.stream().filter(Objects::nonNull).distinct().toList();
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, DELETED)
                .in(ImageEntity::getId, distinct));
        if (rows.isEmpty()) {
            throw BizException.notFound("回收站里没有找到这些图片，可能已经被彻底删除");
        }
        return rows;
    }

    /** 搬到回收站目录；解析配置失败或后端不支持就返回 null（只打数据库标记） */
    private String moveRemoteToTrash(ImageEntity entity) {
        try {
            StorageConfigEntity config = storageConfigService.requireById(entity.getStorageConfigId());
            return trashMover.moveToTrash(storageConfigService.toSetting(config), entity.getObjectKey());
        } catch (Exception e) {
            log.warn("解析存储配置失败，回收站只打数据库标记: {}", e.getMessage());
            return null;
        }
    }

    /** 从回收站目录搬回原位 */
    private void restoreRemote(ImageEntity entity) {
        if (!StringUtils.hasText(entity.getTrashKey())) {
            // 没搬过（非 GitHub 后端 / 搬运失败），只需要清掉数据库标记
            return;
        }
        StorageConfigEntity config = storageConfigService.requireById(entity.getStorageConfigId());
        trashMover.restore(storageConfigService.toSetting(config), entity.getObjectKey(), entity.getTrashKey());
    }

    /** 回收站里的图片实际在哪：搬过就用 trash_key，没搬过仍在原位置 */
    private static String effectiveKey(ImageEntity entity) {
        return StringUtils.hasText(entity.getTrashKey()) ? entity.getTrashKey() : entity.getObjectKey();
    }

    /** 删除云端对象；失败只记警告 */
    private void deleteRemoteObject(ImageEntity entity) {
        try {
            String key = effectiveKey(entity);
            StorageConfigEntity config = storageConfigService.requireById(entity.getStorageConfigId());
            StorageService service = storageServiceFactory.get(storageConfigService.typeOf(config));
            if (service.supportsDelete() && StringUtils.hasText(key)) {
                service.delete(storageConfigService.toSetting(config), key);
            }
        } catch (Exception e) {
            log.warn("从存储后端删除对象失败（本地记录仍会清理）: {}", e.getMessage());
        }
    }

    private static boolean isDeleted(ImageEntity entity) {
        return entity.getDeleted() != null && entity.getDeleted() == DELETED;
    }

    public StatsVO stats(AuthUser user) {
        List<Long> ownerIds = userService.visibleOwnerIds(user);
        StatsVO stats = new StatsVO();
        // 统计只算在用的图片，回收站里的不计入
        stats.setTotalCount(imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, LIVE)));
        // 今日 / 近 7 天 / 总量：都按「在用的图片」统计，回收站不算
        stats.setTodayCount(imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, LIVE)
                .ge(ImageEntity::getCreatedAt, LocalDate.now().atStartOfDay())));

        // 一次取出 size 与 storage_config_id，同时算出占用空间和实际用到的后端数量
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getSize, ImageEntity::getStorageConfigId)
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, LIVE));
        stats.setTotalSize(rows.stream().mapToLong(e -> e.getSize() == null ? 0L : e.getSize()).sum());
        stats.setStorageConfigCount(rows.stream()
                .map(ImageEntity::getStorageConfigId)
                .filter(Objects::nonNull)
                .distinct()
                .count());

        stats.setMaxSize(rows.stream().mapToLong(e -> e.getSize() == null ? 0L : e.getSize()).max().orElse(0L));
        stats.setWeekCount(imageMapper.selectCount(Wrappers.<ImageEntity>lambdaQuery()
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, LIVE)
                .ge(ImageEntity::getCreatedAt, LocalDate.now().minusDays(6).atStartOfDay())));

        // 回收站：张数与占用（概览需要提醒"回收站里还压着多少东西"）
        List<ImageEntity> trashed = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getSize)
                .in(ImageEntity::getUserId, ownerIds)
                .eq(ImageEntity::getDeleted, DELETED));
        stats.setTrashCount(trashed.size());
        stats.setTrashSize(trashed.stream().mapToLong(e -> e.getSize() == null ? 0L : e.getSize()).sum());

        // 重复图片：组数 + 每组保留一张后能省下的空间（= 组内总量 - 保留那张的大小）
        List<Map<String, Object>> duplicateGroups = imageMapper.selectMaps(new QueryWrapper<ImageEntity>()
                .select("hash", "sum(size) as total", "min(id) as keep_id")
                .in("user_id", ownerIds)
                .eq("deleted", LIVE)
                .isNotNull("hash")
                .ne("hash", "")
                .groupBy("hash")
                .having("count(*) > 1"));
        stats.setDuplicateGroups(duplicateGroups.size());
        if (!duplicateGroups.isEmpty()) {
            long total = 0L;
            List<Long> keepIds = new ArrayList<>(duplicateGroups.size());
            for (Map<String, Object> group : duplicateGroups) {
                total += longValue(group, "total");
                keepIds.add(longValue(group, "keep_id"));
            }
            long keepSize = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                            .select(ImageEntity::getSize)
                            .in(ImageEntity::getId, keepIds))
                    .stream().mapToLong(e -> e.getSize() == null ? 0L : e.getSize()).sum();
            stats.setDuplicateWastedSize(Math.max(0L, total - keepSize));
        }

        // 分享链接：个数 + 累计浏览（内容管理里的"分享链接"）
        List<ShareLinkEntity> shares = shareLinkMapper.selectList(Wrappers.<ShareLinkEntity>lambdaQuery()
                .in(ShareLinkEntity::getUserId, ownerIds));
        stats.setShareCount(shares.size());
        stats.setShareViews(shares.stream().mapToLong(e -> e.getViewCount() == null ? 0L : e.getViewCount()).sum());

        stats.setTypeDistribution(distribution(ownerIds));

        Map<Long, StorageConfigEntity> configs = storageConfigService.mapByOwners(ownerIds);
        // 概览页的「最近上传」是按日期分组的网格，取 24 条更实用
        stats.setRecent(imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                        .in(ImageEntity::getUserId, ownerIds)
                        .eq(ImageEntity::getDeleted, LIVE)
                        .orderByDesc(ImageEntity::getId)
                        .last("LIMIT 24"))
                .stream().map(e -> toVO(e, configs)).toList());
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
    public DirectoryTreeVO directories(AuthUser user, Long storageConfigId) {
        List<ImageEntity> rows = imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                .select(ImageEntity::getObjectKey)
                .in(ImageEntity::getUserId, userService.visibleOwnerIds(user))
                .eq(ImageEntity::getDeleted, LIVE)
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

    private List<NameValue> distribution(List<Long> ownerIds) {
        List<Map<String, Object>> rows = imageMapper.selectMaps(new QueryWrapper<ImageEntity>()
                .select("storage_type", "count(*) as cnt")
                .in("user_id", ownerIds)
                .eq("deleted", LIVE)
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

    /** 当前用户能看到的所有存储配置（含超级管理员的，普通用户也要能解析图片地址） */
    private Map<Long, StorageConfigEntity> configsOf(AuthUser user) {
        return storageConfigService.mapByOwners(userService.visibleOwnerIds(user));
    }

    private ImageVO toVO(ImageEntity entity, Map<Long, StorageConfigEntity> configs) {
        ImageVO vo = new ImageVO();
        vo.setId(entity.getId());
        vo.setOriginalName(entity.getOriginalName());
        vo.setObjectKey(entity.getObjectKey());
        String directory = directoryOf(entity.getObjectKey());
        vo.setDirectory(directory);
        vo.setDepth(directory.isEmpty() ? 0 : directory.split("/").length);
        // 预览 / 下载 / 复制各按自己的默认 CDN 解析（未配置就是原始地址）
        StorageConfigEntity config = configs.get(entity.getStorageConfigId());
        String origin = localOrigin(config, entity.getUrl());
        String objectKey = entity.getObjectKey();
        // 回收站里的文件已经被搬到 _trash/ 下，地址必须按实际位置算，
        // 否则缩略图 / 大图预览 / 下载都会指着一个早就搬走的旧路径（404）
        if (StringUtils.hasText(entity.getTrashKey())) {
            origin = moveUrl(origin, objectKey, entity.getTrashKey());
            objectKey = entity.getTrashKey();
        }
        vo.setUrl(origin);
        String[] urls = storageCdnService.urlsFor(config, objectKey, origin);
        vo.setPreviewUrl(urls[0]);
        vo.setDownloadUrl(urls[1]);
        vo.setCopyUrl(urls[2]);
        // 后端没有原生缩略图时（GitHub / 对象存储 / 本地存储），给一个按需缩略图的签名地址；
        // 回收站里的图不给缩略图，前端会自动退回原图地址
        String thumbUrl = entity.getThumbUrl();
        if (!StringUtils.hasText(thumbUrl) && !isDeleted(entity)) {
            thumbUrl = thumbnailService.url(entity.getId(), null);
        }
        vo.setThumbUrl(thumbUrl);
        vo.setMimeType(entity.getMimeType());
        vo.setSize(entity.getSize());
        vo.setWidth(entity.getWidth());
        vo.setHeight(entity.getHeight());
        vo.setStorageType(entity.getStorageType());
        vo.setStorageConfigId(entity.getStorageConfigId());
        vo.setStorageConfigName(config == null ? null : config.getName());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setDeletedAt(entity.getDeletedAt());
        vo.setTrashKey(entity.getTrashKey());
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

    /**
     * 本地存储的地址是「上传那一刻」按请求地址拼出来的，换了端口 / 域名 / 反代之后就失效了。
     * 这里在读的时候按当前请求地址重拼一遍；配置里显式写了 publicBaseUrl 的以配置为准。
     */
    private String localOrigin(StorageConfigEntity config, String url) {
        if (config == null || url == null || !StorageType.LOCAL.name().equalsIgnoreCase(config.getType())) {
            return url;
        }
        int index = url.indexOf("/" + LocalStorageService.URL_PREFIX + "/");
        if (index < 0) {
            return url;
        }
        Map<String, String> values = com.qxfly.imagehost.storage.support.Json.readStringMap(config.getConfigJson());
        if (StringUtils.hasText(values.get("publicBaseUrl"))) {
            // 用户显式配了对外前缀，那就以配置为准
            return url;
        }
        return requestBase.base() + url.substring(index);
    }

    /**
     * 把地址里的对象键换成新的（回收站里的文件被搬到 _trash/ 下时用）。
     * 这些地址基本都是「前缀 + 对象键」，所以先按后缀替换；对不上的再退化成整体替换。
     */
    private static String moveUrl(String url, String oldKey, String newKey) {
        if (!StringUtils.hasText(url) || !StringUtils.hasText(oldKey) || !StringUtils.hasText(newKey)) {
            return url;
        }
        if (url.endsWith(oldKey)) {
            return url.substring(0, url.length() - oldKey.length()) + newKey;
        }
        return url.replace(oldKey, newKey);
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

    /**
     * 待入库的内容。
     * <p>
     * 上传走 {@link #ofFile}：multipart 先落到临时文件，之后 sha256、读宽高、写存储全部按流处理，
     * 内存占用与文件大小无关（这是「单张无上限」的前提）。
     * Base64 上传与批量迁移的字节本来就在内存里，走 {@link #ofBytes}。
     */
    private record PendingContent(byte[] bytes, Path file, long length) {

        static PendingContent ofFile(Path file, long length) {
            return new PendingContent(null, file, length);
        }

        static PendingContent ofBytes(byte[] bytes) {
            return new PendingContent(bytes, null, bytes == null ? 0L : bytes.length);
        }

        /** 流式算 sha256（文件来源时一次只读 8KB） */
        String sha256() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                if (bytes != null) {
                    digest.update(bytes);
                } else {
                    try (InputStream in = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
                byte[] result = digest.digest();
                StringBuilder builder = new StringBuilder(result.length * 2);
                for (byte b : result) {
                    builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                    builder.append(Character.forDigit(b & 0xF, 16));
                }
                return builder.toString();
            } catch (Exception e) {
                return null;
            }
        }

        /** 读取图片宽高，失败（如 SVG）返回 null；只读文件头，不整份加载 */
        int[] dimension() {
            try (InputStream in = bytes != null ? new ByteArrayInputStream(bytes) : Files.newInputStream(file);
                 ImageInputStream inputStream = ImageIO.createImageInputStream(in)) {
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

        StorageObject toObject(String objectKey, String originalName, String mimeType) {
            return bytes != null
                    ? StorageObject.ofBytes(objectKey, originalName, mimeType, bytes)
                    : StorageObject.ofFile(objectKey, originalName, mimeType, file, length);
        }
    }
}
