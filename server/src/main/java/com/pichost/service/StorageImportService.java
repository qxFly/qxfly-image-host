package com.pichost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pichost.common.BizException;
import com.pichost.config.AppProperties;
import com.pichost.dto.ImportCandidateVO;
import com.pichost.dto.ImportPreviewVO;
import com.pichost.dto.ImportRequest;
import com.pichost.dto.ImportResultVO;
import com.pichost.entity.ImageEntity;
import com.pichost.entity.StorageConfigEntity;
import com.pichost.mapper.ImageMapper;
import com.pichost.storage.RemoteObject;
import com.pichost.storage.StorageService;
import com.pichost.storage.StorageServiceFactory;
import com.pichost.storage.StorageSetting;
import com.pichost.storage.support.ImageTypes;
import com.pichost.storage.support.ObjectKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把存储后端里"已经存在但平台还没登记"的文件导入图库。
 * 典型场景：仓库/桶里已经有历史图片（别处传的、或接入平台之前传的）。
 * <p>
 * 会尽量拉取全量：GitHub 用 git/trees（被截断时逐级遍历），S3 用 ListObjectsV2 的
 * continuation token 翻页，直到拉完或到达 app.upload.import-max-files 上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageImportService {

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 500;

    private final ImageMapper imageMapper;
    private final StorageConfigService storageConfigService;
    private final StorageServiceFactory storageServiceFactory;
    private final AppProperties appProperties;

    /** 扫描远端文件，返回总览与当前页候选 */
    public ImportPreviewVO preview(Long userId, Long storageConfigId, String prefix, Integer page, Integer size) {
        StorageConfigEntity config = storageConfigService.requireOwned(userId, storageConfigId);
        StorageSetting setting = storageConfigService.toSetting(config);
        StorageService service = requireListable(setting);

        int maxFiles = appProperties.getUpload().getImportMaxFiles();
        List<RemoteObject> remote = service.list(setting, resolvePrefix(prefix), maxFiles);
        Set<String> existing = existingKeys(userId, config.getId());
        List<String> allowed = allowedExtensions();

        List<ImportCandidateVO> all = new ArrayList<>();
        int importedCount = 0;
        for (RemoteObject object : remote) {
            if (!isImportable(object.objectKey(), allowed)) {
                continue;
            }
            boolean already = existing.contains(object.objectKey());
            if (already) {
                importedCount++;
            }
            all.add(toCandidate(object, already));
        }

        int pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int currentPage = page == null || page < 1 ? 1 : page;
        int totalPages = all.isEmpty() ? 0 : (all.size() + pageSize - 1) / pageSize;
        int from = Math.min((currentPage - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());

        ImportPreviewVO vo = new ImportPreviewVO();
        vo.setScanned(all.size());
        vo.setImported(importedCount);
        vo.setImportable(all.size() - importedCount);
        vo.setTruncated(remote.size() >= maxFiles);
        vo.setPage(currentPage);
        vo.setSize(pageSize);
        vo.setTotalPages(totalPages);
        vo.setCandidates(new ArrayList<>(all.subList(from, to)));
        if (vo.isTruncated()) {
            log.warn("远端文件数达到扫描上限 {}，可能未拉全（可调大 IMPORT_MAX_FILES）", maxFiles);
        }
        return vo;
    }

    /**
     * 执行导入。
     * objectKeys 为空表示导入扫描到的全部未导入文件（服务端自己遍历，不受前端分页影响）。
     */
    @Transactional
    public ImportResultVO importObjects(Long userId, ImportRequest request) {
        StorageConfigEntity config = storageConfigService.requireOwned(userId, request.getStorageConfigId());
        StorageSetting setting = storageConfigService.toSetting(config);
        StorageService service = requireListable(setting);

        int maxFiles = appProperties.getUpload().getImportMaxFiles();
        List<RemoteObject> remote = service.list(setting, resolvePrefix(request.getPrefix()), maxFiles);
        Set<String> existing = existingKeys(userId, config.getId());
        Set<String> wanted = request.getObjectKeys() == null
                ? Set.of()
                : new HashSet<>(request.getObjectKeys());
        List<String> allowed = allowedExtensions();

        ImportResultVO result = new ImportResultVO();
        result.setScanned(remote.size());
        for (RemoteObject object : remote) {
            if (!isImportable(object.objectKey(), allowed)) {
                continue;
            }
            if (!wanted.isEmpty() && !wanted.contains(object.objectKey())) {
                continue;
            }
            if (existing.contains(object.objectKey())) {
                result.setSkipped(result.getSkipped() + 1);
                continue;
            }

            ImageEntity entity = new ImageEntity();
            entity.setUserId(userId);
            entity.setStorageConfigId(config.getId());
            entity.setStorageType(config.getType());
            entity.setOriginalName(ObjectKeys.fileName(object.objectKey()));
            entity.setObjectKey(object.objectKey());
            entity.setUrl(StringUtils.hasText(object.url())
                    ? object.url()
                    : service.publicUrl(setting, object.objectKey()));
            entity.setMimeType(ImageTypes.mimeOf(object.objectKey()));
            entity.setSize(object.size());
            // 导入的文件不重新下载，因此没有 sha256、也读不到宽高
            imageMapper.insert(entity);
            existing.add(object.objectKey());
            result.setImported(result.getImported() + 1);
        }
        log.info("从存储导入完成：远端扫描 {}，新增 {}，跳过 {}",
                result.getScanned(), result.getImported(), result.getSkipped());
        return result;
    }

    private ImportCandidateVO toCandidate(RemoteObject object, boolean imported) {
        ImportCandidateVO vo = new ImportCandidateVO();
        vo.setObjectKey(object.objectKey());
        vo.setFileName(ObjectKeys.fileName(object.objectKey()));
        vo.setSize(object.size());
        vo.setUrl(object.url());
        vo.setLastModified(object.lastModified());
        vo.setImported(imported);
        return vo;
    }

    private StorageService requireListable(StorageSetting setting) {
        StorageService service = storageServiceFactory.get(setting.type());
        if (!service.supportsList()) {
            throw new BizException("【" + setting.type().getLabel()
                    + "】暂不支持列举已有文件，无法导入。目前支持 GitHub 与 S3 兼容存储（含 R2 / MinIO）");
        }
        return service;
    }

    private boolean isImportable(String objectKey, List<String> allowed) {
        String extension = ImageTypes.normalize(ObjectKeys.extension(objectKey));
        return !extension.isEmpty() && allowed.contains(extension);
    }

    private List<String> allowedExtensions() {
        return appProperties.getUpload().getAllowedExtensions().stream()
                .map(ImageTypes::normalize)
                .collect(Collectors.toList());
    }

    /** 传空即从仓库/桶根目录开始扫描；界面会默认预填配置里的路径前缀 */
    private String resolvePrefix(String prefix) {
        return StringUtils.hasText(prefix) ? prefix.trim() : null;
    }

    private Set<String> existingKeys(Long userId, Long storageConfigId) {
        return imageMapper.selectList(Wrappers.<ImageEntity>lambdaQuery()
                        .select(ImageEntity::getObjectKey)
                        .eq(ImageEntity::getUserId, userId)
                        .eq(ImageEntity::getStorageConfigId, storageConfigId))
                .stream()
                .map(ImageEntity::getObjectKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
