package com.pichost.controller;

import com.pichost.common.ApiResult;
import com.pichost.common.PageResult;
import com.pichost.dto.Base64UploadRequest;
import com.pichost.dto.DirectoryTreeVO;
import com.pichost.dto.ImageVO;
import com.pichost.dto.ImportPreviewVO;
import com.pichost.dto.ImportRequest;
import com.pichost.dto.ImportResultVO;
import com.pichost.dto.MigrateRequest;
import com.pichost.dto.RepoDedupeRequest;
import com.pichost.dto.RepoDedupeResultVO;
import com.pichost.dto.RepoMoveCheckVO;
import com.pichost.dto.RepoMoveRequest;
import com.pichost.dto.RepoMoveResultVO;
import com.pichost.dto.MigrationTaskVO;
import com.pichost.dto.StatsVO;
import com.pichost.security.SecurityUtils;
import com.pichost.service.ImageService;
import com.pichost.service.MigrationService;
import com.pichost.service.RepoDedupeService;
import com.pichost.service.RepoMoveService;
import com.pichost.service.StorageImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片接口。
 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;
    private final StorageImportService storageImportService;
    private final MigrationService migrationService;
    private final RepoMoveService repoMoveService;
    private final RepoDedupeService repoDedupeService;

    /** 单张上传（multipart/form-data，字段名 file） */
    @PostMapping("/upload")
    public ApiResult<ImageVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "storageConfigId", required = false) Long storageConfigId) {
        return ApiResult.ok(imageService.upload(SecurityUtils.currentUserId(), file, storageConfigId));
    }

    /** 多张上传（字段名 files） */
    @PostMapping("/upload/batch")
    public ApiResult<List<ImageVO>> uploadBatch(@RequestParam("files") MultipartFile[] files,
                                                @RequestParam(value = "storageConfigId", required = false) Long storageConfigId) {
        return ApiResult.ok(imageService.uploadBatch(SecurityUtils.currentUserId(), files, storageConfigId));
    }

    /** Base64 上传，便于桌面端 / 编辑器插件调用 */
    @PostMapping("/upload/base64")
    public ApiResult<ImageVO> uploadBase64(@Valid @RequestBody Base64UploadRequest request) {
        return ApiResult.ok(imageService.uploadBase64(SecurityUtils.currentUserId(), request));
    }

    @GetMapping
    public ApiResult<PageResult<ImageVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "30") long size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long storageConfigId,
                                               @RequestParam(required = false) String directory,
                                               @RequestParam(required = false) String matchMode) {
        return ApiResult.ok(imageService.page(SecurityUtils.currentUserId(),
                page, size, keyword, storageConfigId, directory, matchMode));
    }

    /** 目录树：按目录层级浏览图库 */
    @GetMapping("/directories")
    public ApiResult<DirectoryTreeVO> directories(@RequestParam(required = false) Long storageConfigId) {
        return ApiResult.ok(imageService.directories(SecurityUtils.currentUserId(), storageConfigId));
    }

    @GetMapping("/{id}")
    public ApiResult<ImageVO> get(@PathVariable Long id) {
        return ApiResult.ok(imageService.get(SecurityUtils.currentUserId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        imageService.delete(SecurityUtils.currentUserId(), id);
        return ApiResult.ok();
    }

    @GetMapping("/stats")
    public ApiResult<StatsVO> stats() {
        return ApiResult.ok(imageService.stats(SecurityUtils.currentUserId()));
    }

    /**
     * 预览存储后端里已有的文件（用于把仓库/桶里已有的图片导入图库）。
     * 返回结果会标记每个文件是否已在平台中。
     */
    @GetMapping("/import/preview")
    public ApiResult<ImportPreviewVO> previewImport(
            @RequestParam Long storageConfigId,
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(storageImportService.preview(
                SecurityUtils.currentUserId(), storageConfigId, prefix, page, size));
    }

    /** 执行导入 */
    @PostMapping("/import")
    public ApiResult<ImportResultVO> importFiles(@Valid @RequestBody ImportRequest request) {
        return ApiResult.ok(storageImportService.importObjects(SecurityUtils.currentUserId(), request));
    }

    /**
     * 批量迁移：把已保存的图片搬到另一个路径前缀或另一个存储后端。
     * 后台执行，立刻返回 taskId，用下面的接口轮询进度。
     */
    @PostMapping("/migrate")
    public ApiResult<MigrationTaskVO> startMigrate(@Valid @RequestBody MigrateRequest request) {
        return ApiResult.ok(migrationService.start(SecurityUtils.currentUserId(), request));
    }

    /**
     * 【临时功能】GitHub 仓库目录整理：预览。
     * 把某个目录下的图片文件按系统存储规则搬到另一个目录（同仓库移动，复用原有 blob，不下载重传）。
     */
    @PostMapping("/repo-move/preview")
    public ApiResult<RepoMoveResultVO> repoMovePreview(@Valid @RequestBody RepoMoveRequest request) {
        return ApiResult.ok(repoMoveService.preview(SecurityUtils.currentUserId(), request));
    }

    /** 【临时功能】仓库重复文件清理：分析（只读） */
    @PostMapping("/repo-dedupe/preview")
    public ApiResult<RepoDedupeResultVO> repoDedupePreview(@Valid @RequestBody RepoDedupeRequest request) {
        return ApiResult.ok(repoDedupeService.preview(SecurityUtils.currentUserId(), request));
    }

    /** 【临时功能】仓库重复文件清理：执行删除 */
    @PostMapping("/repo-dedupe/execute")
    public ApiResult<RepoDedupeResultVO> repoDedupeExecute(@Valid @RequestBody RepoDedupeRequest request) {
        return ApiResult.ok(repoDedupeService.execute(SecurityUtils.currentUserId(), request));
    }

    /** 【临时功能】GitHub 仓库目录整理：自检（只读，用于排查静默失败） */
    @GetMapping("/repo-move/diagnose")
    public ApiResult<List<RepoMoveCheckVO>> repoMoveDiagnose(@RequestParam Long storageConfigId,
                                                             @RequestParam(required = false) String sourceDirectory,
                                                             @RequestParam(required = false) String targetDirectory,
                                                             @RequestParam(defaultValue = "false") boolean includeSubdirectories,
                                                             @RequestParam(defaultValue = "true") boolean keepFileName,
                                                             @RequestParam(defaultValue = "false") boolean layered) {
        RepoMoveRequest request = new RepoMoveRequest();
        request.setStorageConfigId(storageConfigId);
        if (sourceDirectory != null) {
            request.setSourceDirectory(sourceDirectory);
        }
        if (targetDirectory != null) {
            request.setTargetDirectory(targetDirectory);
        }
        request.setIncludeSubdirectories(includeSubdirectories);
        request.setKeepFileName(keepFileName);
        request.setLayered(layered);
        return ApiResult.ok(repoMoveService.diagnose(SecurityUtils.currentUserId(), request));
    }

    /** 【临时功能】GitHub 仓库目录整理：执行 */
    @PostMapping("/repo-move/execute")
    public ApiResult<RepoMoveResultVO> repoMoveExecute(@Valid @RequestBody RepoMoveRequest request) {
        return ApiResult.ok(repoMoveService.execute(SecurityUtils.currentUserId(), request));
    }

    /** 后台迁移任务列表（新的在前），用于任务面板 */
    @GetMapping("/migrate")
    public ApiResult<List<MigrationTaskVO>> migrateTasks() {
        return ApiResult.ok(migrationService.list(SecurityUtils.currentUserId()));
    }

    /** 查询单个迁移任务的进度 */
    @GetMapping("/migrate/{taskId}")
    public ApiResult<MigrationTaskVO> migrateStatus(@PathVariable String taskId) {
        return ApiResult.ok(migrationService.status(SecurityUtils.currentUserId(), taskId));
    }

    /** 取消迁移（已完成的图片不受影响） */
    @PostMapping("/migrate/{taskId}/cancel")
    public ApiResult<MigrationTaskVO> cancelMigrate(@PathVariable String taskId) {
        return ApiResult.ok(migrationService.cancel(SecurityUtils.currentUserId(), taskId));
    }
}
