package com.qxfly.imagehost.storage;

/**
 * 存储后端统一抽象。新增一种图床只需实现该接口并注册为 Spring Bean。
 */
public interface StorageService {

    /** 支持的存储类型 */
    StorageType type();

    /**
     * 上传对象。
     */
    UploadResult upload(StorageSetting setting, StorageObject object);

    /**
     * 删除对象，不支持删除的实现可抛出业务异常。
     */
    default void delete(StorageSetting setting, String objectKey) {
        throw new com.qxfly.imagehost.common.BizException("【" + type().getLabel() + "】不支持删除操作");
    }

    /**
     * 连通性/凭证校验，失败时抛出异常。
     */
    default void test(StorageSetting setting) {
        // 默认无操作
    }

    /**
     * 校验通过后给用户看的说明（例如本地存储会回显目录与可用空间）。
     * 返回 null / 空串时用统一话术「连接成功，凭证有效」。
     */
    default String testMessage(StorageSetting setting) {
        return null;
    }

    /** 该后端是否支持删除 */
    default boolean supportsDelete() {
        return true;
    }

    /** 是否支持列举已有对象（决定能否"从存储导入"） */
    default boolean supportsList() {
        return false;
    }

    /**
     * 列举已有对象，用于把仓库里已存在、但平台还没登记的图片导入进来。
     *
     * @param prefix 只列举该前缀下的对象，可为空
     * @param limit  最多返回多少条
     */
    default java.util.List<RemoteObject> list(StorageSetting setting, String prefix, int limit) {
        throw new com.qxfly.imagehost.common.BizException(
                "【" + type().getLabel() + "】不支持列举已有文件，无法导入");
    }

    /** 由对象键推导公开访问地址（不发起请求） */
    default String publicUrl(StorageSetting setting, String objectKey) {
        throw new com.qxfly.imagehost.common.BizException(
                "【" + type().getLabel() + "】无法推导文件的访问地址");
    }

    /**
     * 是否支持按对象键下载原始内容（迁移时需要）。
     * 不支持时会退化为直接拉取该图片已保存的访问地址。
     */
    default boolean supportsDownload() {
        return false;
    }

    /** 下载对象原始字节 */
    default byte[] download(StorageSetting setting, String objectKey) {
        throw new com.qxfly.imagehost.common.BizException("【" + type().getLabel() + "】不支持按对象键下载");
    }

    /**
     * 是否支持在存储内部移动对象（不重新上传）。
     * 回收站搬运会用这个能力：本地存储是磁盘上的改名，零流量。
     */
    default boolean supportsMove() {
        return false;
    }

    /** 在存储内部移动/改名对象（目标已存在时由调用方先删除） */
    default void move(StorageSetting setting, String fromKey, String toKey) {
        throw new com.qxfly.imagehost.common.BizException("【" + type().getLabel() + "】不支持移动文件");
    }

    /** 对象是否存在（移动类操作的幂等判断用） */
    default boolean exists(StorageSetting setting, String objectKey) {
        throw new com.qxfly.imagehost.common.BizException("【" + type().getLabel() + "】不支持检查文件是否存在");
    }
}
