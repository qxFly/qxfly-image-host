package com.qxfly.imagehost.dto;

import com.qxfly.imagehost.storage.StorageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class StorageConfigRequest {

    @NotBlank(message = "配置名称不能为空")
    @Size(max = 64, message = "配置名称最长 64 个字符")
    private String name;

    @NotNull(message = "存储类型不能为空")
    private StorageType type;

    /** 各存储类型的参数，键值含义见 GET /api/storage-configs/types */
    private Map<String, String> config = new LinkedHashMap<>();

    private Boolean isDefault;

    private Boolean enabled;

    @Size(max = 255, message = "备注最长 255 个字符")
    private String remark;
}
