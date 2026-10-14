package com.qxfly.imagehost.controller;

import com.qxfly.imagehost.common.ApiResult;
import com.qxfly.imagehost.common.PageResult;
import com.qxfly.imagehost.dto.Base64UploadRequest;
import com.qxfly.imagehost.dto.DirectoryTreeVO;
import com.qxfly.imagehost.dto.ImageDownloadRequest;
import com.qxfly.imagehost.dto.ImageLinkVO;
import com.qxfly.imagehost.dto.ImageIdsRequest;
import com.qxfly.imagehost.dto.ImageVO;
import com.qxfly.imagehost.dto.ImportPreviewVO;
import com.qxfly.imagehost.dto.ImportRequest;
import com.qxfly.imagehost.dto.ImportResultVO;
import com.qxfly.imagehost.dto.MigrateRequest;
import com.qxfly.imagehost.dto.RepoDedupeRequest;
import com.qxfly.imagehost.dto.RepoDedupeResultVO;
import com.qxfly.imagehost.dto.RepoMoveCheckVO;
import com.qxfly.imagehost.dto.RepoMoveRequest;
import com.qxfly.imagehost.dto.RepoMoveResultVO;
import com.qxfly.imagehost.dto.MigrationTaskVO;
import com.qxfly.imagehost.dto.StatsVO;
import com.qxfly.imagehost.entity.ImageEntity;
import com.qxfly.imagehost.security.SecurityUtils;
import com.qxfly.imagehost.service.ImageDownloadService;
import com.qxfly.imagehost.service.ImageService;
import com.qxfly.imagehost.service.MigrationService;
import com.qxfly.imagehost.service.RepoDedupeService;
import com.qxfly.imagehost.service.RepoMoveService;
import com.qxfly.imagehost.service.StorageImportService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 图片接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private static final DateTimeFormatter ZIP_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ImageService imageService;
    private final ImageDownloadService imageDownloadService;
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
        return ApiResult.ok(imageService.page(SecurityUtils.currentUser(),
                page, size, keyword, storageConfigId, directory, matchMode));
    }

    /** 目录树：按目录层级浏览图库 */
    @GetMapping("/directories")
    public ApiResult<DirectoryTreeVO> directories(@RequestParam(required = false) Long storageConfigId) {
        return ApiResult.ok(imageService.directories(SecurityUtils.currentUser(), storageConfigId));
    }

    @GetMapping("/{id}")
    public ApiResult<ImageVO> get(@PathVariable Long id) {
        return ApiResult.ok(imageService.get(SecurityUtils.currentUser(), id));
    }

    /**
     * 删除图片 = 移入回收站（逻辑删除）。
     * 云端文件保留，可以在回收站里恢复，也可以在回收站里再删一次做物理删除。
     */
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        imageService.moveToTrash(SecurityUtils.currentUserId(), id);
        return ApiResult.ok();
    }

    /**
     * 批量删除 = 批量移入回收站（仅超级管理员，权限见 SecurityConfig）。
     * 与单张删除同一套语义：云端文件搬进回收站目录，可以在回收站恢复或彻底删除。
     */
    @PostMapping("/delete/batch")
    public ApiResult<Integer> deleteBatch(@Valid @RequestBody ImageIdsRequest request) {
        return ApiResult.ok(imageService.batchMoveToTrash(SecurityUtils.currentUser(), request.getImageIds()));
    }

    /** 这张图可用的全部链接（原始地址 + 各 CDN + 代理下载），供「选择复制哪种链接」的弹窗使用 */
    @GetMapping("/{id}/links")
    public ApiResult<List<ImageLinkVO>> links(@PathVariable Long id) {
        return ApiResult.ok(imageService.links(SecurityUtils.currentUser(), id));
    }

    /** 回收站列表（仅超级管理员，权限见 SecurityConfig） */
    @GetMapping("/trash")
    public ApiResult<PageResult<ImageVO>> trash(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "30") long size,
                                                @RequestParam(required = false) String keyword) {
        return ApiResult.ok(imageService.pageTrash(SecurityUtils.currentUser(), page, size, keyword));
    }

    /** 从回收站恢复 */
    @PostMapping("/trash/restore")
    public ApiResult<Integer> restore(@Valid @RequestBody ImageIdsRequest request) {
        return ApiResult.ok(imageService.restore(SecurityUtils.currentUser(), request.getImageIds()));
    }

    /** 彻底删除：同时删掉云端对象与本地记录，不可恢复 */
    @PostMapping("/trash/delete")
    public ApiResult<Integer> purge(@Valid @RequestBody ImageIdsRequest request) {
        return ApiResult.ok(imageService.purge(SecurityUtils.currentUser(), request.getImageIds()));
    }

    /** 清空回收站 */
    @PostMapping("/trash/clear")
    public ApiResult<Integer> clearTrash() {
        return ApiResult.ok(imageService.clearTrash(SecurityUtils.currentUser()));
    }

    /**
     * 下载单张原图。
     * 由服务端代理取回内容再回传：浏览器直接访问存储直链既带不了 JWT，也进不去私有仓库 / 私有桶。
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        ImageDownloadService.ImageFile file = imageDownloadService.download(SecurityUtils.currentUser(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.fileName()))
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }

    /**
     * 批量下载：把选中的图片打包成 zip，边取边写，不在内存里囤整包。
     * 单张失败只写进包内的「下载失败清单.txt」，不影响其余图片。
     */
    @PostMapping("/download/batch")
    public void downloadBatch(@Valid @RequestBody ImageDownloadRequest request, HttpServletResponse response)
            throws IOException {
        var currentUser = SecurityUtils.currentUser();
        Long userId = currentUser.id();
        // 先校验再设响应头：图片不存在 / 越权时还能正常返回 JSON 错误
        List<ImageEntity> images = imageDownloadService.resolveBatch(currentUser, request.getImageIds());
        String zipName = "qxfly-images-" + LocalDateTime.now().format(ZIP_TIME_FORMAT) + ".zip";
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(zipName));
        // 打包数量在开始传输前就能确定，先告诉前端要下多少张
        response.setHeader("X-Image-Count", String.valueOf(images.size()));
        log.info("用户 {} 批量下载 {} 张图片：{}", userId, images.size(), zipName);
        imageDownloadService.writeZip(images, response.getOutputStream());
    }

    /**
     * 附件名同时给出 ASCII 与 RFC 5987 两种写法，中文文件名在各浏览器里都不会乱码。
     */
    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    @GetMapping("/stats")
    public ApiResult<StatsVO> stats() {
        return ApiResult.ok(imageService.stats(SecurityUtils.currentUser()));
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
