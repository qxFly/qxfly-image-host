package com.qxfly.imagehost.storage;

/**
 * 存储后端中已存在的对象（用于"从存储导入"）。
 *
 * @param objectKey    对象键
 * @param size         字节数，未知为 0
 * @param url          可公开访问的地址
 * @param lastModified 最后修改时间（ISO 字符串），可能为空
 */
public record RemoteObject(String objectKey, long size, String url, String lastModified) {
}
