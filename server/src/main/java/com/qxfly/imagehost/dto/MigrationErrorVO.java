package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 迁移失败归并后的一个条目：同一种原因合并成一条。
 */
@Data
public class MigrationErrorVO {

    /** 失败原因（已把 URL 等易变部分归一化，便于同类合并） */
    private String reason;
    /** 该原因导致的失败张数（完整计数） */
    private int count;
    /** 示例文件名（最多若干个） */
    private List<String> samples = new ArrayList<>();
}
