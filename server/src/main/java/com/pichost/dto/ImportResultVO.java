package com.pichost.dto;

import lombok.Data;

/**
 * 导入结果统计。
 */
@Data
public class ImportResultVO {

    /** 扫描到的文件数 */
    private int scanned;
    /** 新导入的数量 */
    private int imported;
    /** 因已存在而跳过的数量 */
    private int skipped;
}
