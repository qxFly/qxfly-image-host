package com.qxfly.imagehost.storage;

import com.qxfly.imagehost.common.BizException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 待写入存储后端的对象。
 * <p>
 * 内容有两种来源：
 * <ul>
 *   <li><b>文件</b>（{@link #ofFile}）：上传时先把 multipart 落到临时文件，整条链路按流处理，
 *       内存占用与文件大小无关 —— 这是「单张无上限」能真正坐实的原因</li>
 *   <li><b>字节数组</b>（{@link #ofBytes}）：Base64 上传、批量迁移这类内容本来就在内存里的场景</li>
 * </ul>
 * 能用流的实现（S3 系 / 本地存储 / Release 附件）请用 {@link #openStream()}、
 * {@link #contentFile()}、{@link #fileBacked()}；
 * 只有必须拿到整份内容的实现（GitHub Contents / ImgBB / SM.MS 这类要 Base64 协议）
 * 才调用 {@link #content()}，它会按需把文件读进内存。
 */
public final class StorageObject {

    private final String objectKey;
    private final String fileName;
    private final String contentType;
    /** 内存来源，与 file 二选一 */
    private final byte[] bytes;
    /** 文件来源，与 bytes 二选一 */
    private final Path file;
    private final long length;

    private StorageObject(String objectKey, String fileName, String contentType,
                          byte[] bytes, Path file, long length) {
        this.objectKey = objectKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.bytes = bytes;
        this.file = file;
        this.length = length;
    }

    /** 内容在内存里（等价于 {@link #ofBytes}） */
    public StorageObject(String objectKey, String fileName, String contentType, byte[] content) {
        this(objectKey, fileName, contentType, content, null, content == null ? 0L : content.length);
    }

    public static StorageObject ofBytes(String objectKey, String fileName, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException("上传内容为空");
        }
        return new StorageObject(objectKey, fileName, contentType, content, null, content.length);
    }

    public static StorageObject ofFile(String objectKey, String fileName, String contentType, Path file, long length) {
        if (file == null || !Files.isRegularFile(file)) {
            throw new BizException("待上传的临时文件不存在");
        }
        return new StorageObject(objectKey, fileName, contentType, null, file, length > 0 ? length : sizeOf(file));
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    public String objectKey() {
        return objectKey;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    /** 字节数 */
    public long size() {
        return length;
    }

    /** 内容是否在磁盘文件里（能用流的实现据此走流式路径） */
    public boolean fileBacked() {
        return file != null;
    }

    /** 内容文件；内存来源时返回 null */
    public Path contentFile() {
        return file;
    }

    /** 整份内容；文件来源时按需读进内存（大文件请改用流） */
    public byte[] content() {
        if (bytes != null) {
            return bytes;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BizException("读取待上传文件失败: " + e.getMessage());
        }
    }

    /** 内容流，调用方负责关闭 */
    public InputStream openStream() {
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new BizException("读取待上传文件失败: " + e.getMessage());
        }
    }

    public String baseName() {
        if (objectKey == null) {
            return fileName;
        }
        int index = objectKey.lastIndexOf('/');
        return index < 0 ? objectKey : objectKey.substring(index + 1);
    }
}
