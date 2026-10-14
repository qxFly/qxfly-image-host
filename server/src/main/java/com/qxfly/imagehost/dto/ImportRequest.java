package com.qxfly.imagehost.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 从存储后端导入已有文件的请求。
 */
@Data
public class ImportRequest {

    @NotNull(message = "请选择存储配置")
    private Long storageConfigId;

    /** 只扫描该前缀，留空则用配置里的路径前缀 */
    private String prefix;

    /** 指定要导入的对象键；为空表示导入扫描到的全部未导入文件 */
    private List<String> objectKeys;

    /** 最多扫描多少个对象 */
    private Integer limit;
}
