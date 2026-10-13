package com.pichost.dto;

import com.pichost.storage.StorageType;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StorageConfigVO {

    private Long id;
    private String name;
    private StorageType type;
    private String typeLabel;
    /** 敏感字段以 ****** 打码返回 */
    private Map<String, String> config = new LinkedHashMap<>();
    private Boolean isDefault;
    private Boolean enabled;
    /** 该类型是否支持列举已有文件（决定能否"从存储导入"） */
    private Boolean supportsList;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
