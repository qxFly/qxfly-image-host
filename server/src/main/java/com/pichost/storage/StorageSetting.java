package com.pichost.storage;

import com.pichost.common.BizException;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验通过的存储配置视图，屏蔽了具体的 Map 取值细节。
 */
public class StorageSetting {

    private final StorageType type;
    private final Map<String, String> values;

    private StorageSetting(StorageType type, Map<String, String> values) {
        this.type = type;
        this.values = values;
    }

    public static StorageSetting of(StorageType type, Map<String, String> rawValues) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawValues != null) {
            rawValues.forEach((k, v) -> values.put(k, v == null ? null : v.trim()));
        }
        // 补齐未提供的默认值
        for (StorageField field : type.getFields()) {
            if (!StringUtils.hasText(values.get(field.key())) && StringUtils.hasText(field.defaultValue())) {
                values.put(field.key(), field.defaultValue());
            }
        }
        StorageSetting setting = new StorageSetting(type, values);
        setting.validate();
        return setting;
    }

    private void validate() {
        List<String> missing = type.getFields().stream()
                .filter(f -> f.required() && !StringUtils.hasText(get(f.key())))
                .map(StorageField::label)
                .toList();
        if (!missing.isEmpty()) {
            throw new BizException("存储配置【" + type.getLabel() + "】缺少必填项: " + String.join("、", missing));
        }
    }

    public StorageType type() {
        return type;
    }

    public Map<String, String> asMap() {
        return values;
    }

    public String get(String key) {
        String value = values.get(key);
        return StringUtils.hasText(value) ? value : null;
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    public boolean getBool(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 去掉首尾斜杠，并补上末尾斜杠的路径前缀规范化 */
    public String pathPrefix() {
        String prefix = get("pathPrefix", "");
        if (prefix.isEmpty()) {
            return "";
        }
        String normalized = prefix.replaceAll("^/+", "").replaceAll("/+$", "");
        return normalized.isEmpty() ? "" : normalized + "/";
    }
}
