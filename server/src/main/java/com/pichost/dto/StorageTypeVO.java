package com.pichost.dto;

import com.pichost.storage.StorageField;

import java.util.List;

/**
 * 供前端动态渲染存储配置表单。
 */
public record StorageTypeVO(
        String name,
        String label,
        String shortLabel,
        String description,
        List<StorageField> fields) {
}
