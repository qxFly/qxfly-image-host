package com.pichost.storage;

/**
 * 待写入存储后端的对象。
 *
 * @param objectKey   对象键（含路径前缀）
 * @param fileName    原始文件名
 * @param contentType MIME 类型
 * @param content     文件字节
 */
public record StorageObject(String objectKey, String fileName, String contentType, byte[] content) {

    public long size() {
        return content == null ? 0L : content.length;
    }

    public String baseName() {
        if (objectKey == null) {
            return fileName;
        }
        int index = objectKey.lastIndexOf('/');
        return index < 0 ? objectKey : objectKey.substring(index + 1);
    }
}
