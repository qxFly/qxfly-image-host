package com.qxfly.imagehost.dto;

import lombok.Data;

/**
 * 「从存储导入」的候选文件。
 */
@Data
public class ImportCandidateVO {

    private String objectKey;
    private String fileName;
    private long size;
    private String url;
    private String lastModified;
    /** 平台里是否已有该对象记录 */
    private boolean imported;
}
