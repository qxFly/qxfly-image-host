package com.pichost.storage;

import java.util.List;

/**
 * 存储后端配置项描述。
 * 前端根据该结构自动渲染配置表单，新增存储后端时无需改动前端代码。
 *
 * @param key          配置键
 * @param label        中文名称
 * @param type         控件类型：text / password / number / switch / select / textarea
 * @param required     是否必填
 * @param secret       是否敏感字段（落库加密、回显打码）
 * @param placeholder  输入提示
 * @param defaultValue 默认值
 * @param options      select 类型可选项
 * @param help         说明文案
 */
public record StorageField(
        String key,
        String label,
        String type,
        boolean required,
        boolean secret,
        String placeholder,
        String defaultValue,
        List<String> options,
        String help) {

    /** 必填文本 */
    public static StorageField req(String key, String label, String help) {
        return new StorageField(key, label, "text", true, false, null, null, List.of(), help);
    }

    /** 可选文本 */
    public static StorageField opt(String key, String label, String defaultValue, String help) {
        return new StorageField(key, label, "text", false, false, null, defaultValue, List.of(), help);
    }

    /** 必填密钥 */
    public static StorageField pwd(String key, String label, String help) {
        return new StorageField(key, label, "password", true, true, null, null, List.of(), help);
    }

    /** 可选密钥 */
    public static StorageField optPwd(String key, String label, String help) {
        return new StorageField(key, label, "password", false, true, null, null, List.of(), help);
    }

    /** 可选数字 */
    public static StorageField num(String key, String label, String defaultValue, String help) {
        return new StorageField(key, label, "number", false, false, null, defaultValue, List.of(), help);
    }

    /** 开关 */
    public static StorageField sw(String key, String label, boolean defaultValue, String help) {
        return new StorageField(key, label, "switch", false, false, null, String.valueOf(defaultValue), List.of(), help);
    }

    /** 下拉选择 */
    public static StorageField sel(String key, String label, String defaultValue, List<String> options, String help) {
        return new StorageField(key, label, "select", true, false, null, defaultValue, options, help);
    }
}
