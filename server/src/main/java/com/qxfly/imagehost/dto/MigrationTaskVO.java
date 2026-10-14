package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 迁移任务状态。
 */
@Data
public class MigrationTaskVO {

    private String taskId;
    /** PENDING / RUNNING / SUCCESS / PARTIAL / FAILED / CANCELLED */
    private String status;
    private String sourceName;
    private String targetName;
    /** 本次任务的并发数 */
    private int concurrency;

    private int total;
    private int done;
    private int succeeded;
    private int failed;
    private int skipped;
    private int percent;

    private String currentFile;
    private String message;
    private long startedAt;
    private Long finishedAt;
    /** 失败原因，按原因归并，按失败数量从多到少排列 */
    private List<MigrationErrorVO> errors = new ArrayList<>();
}
