package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「从存储导入」的扫描结果：总览 + 当前页候选文件。
 * 远端文件可能有几万个，一次性全塞给浏览器没有意义，所以只回传一页。
 */
@Data
public class ImportPreviewVO {

    /** 扫描到的图片文件总数 */
    private int scanned;
    /** 其中已在平台里的数量 */
    private int imported;
    /** 其中待导入的数量 */
    private int importable;
    /** 是否因为扫描上限而没能拉全（可调大 app.upload.import-max-files） */
    private boolean truncated;

    private int page;
    private int size;
    private int totalPages;

    private List<ImportCandidateVO> candidates = new ArrayList<>();
}
