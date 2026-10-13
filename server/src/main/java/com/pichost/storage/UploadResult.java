package com.pichost.storage;

/**
 * 上传结果。
 *
 * @param objectKey 存储后端中的对象键（删除时使用）
 * @param url       可公开访问的地址
 * @param thumbUrl  缩略图地址，可为空
 */
public record UploadResult(String objectKey, String url, String thumbUrl) {

    public static UploadResult of(String objectKey, String url) {
        return new UploadResult(objectKey, url, null);
    }
}
