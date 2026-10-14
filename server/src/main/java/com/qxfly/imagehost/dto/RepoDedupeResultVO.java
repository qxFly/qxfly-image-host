package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 重复文件分析 / 清理结果。
 */
@Data
public class RepoDedupeResultVO {

    private boolean dryRun;
    /** 扫描到的图片总数 */
    private int scanned;
    /** 存在重名的组数 */
    private int duplicateGroups;
    /** 组内内容完全相同（真重复）的组数 */
    private int sameShaGroups;
    /** 组内内容不同（同名但不同图）的组数 —— 这些不能删 */
    private int diffShaGroups;

    /** 带 -N 后缀、且能找到同名基名的数量 */
    private int suffixedWithBase;
    /** 带 -N 后缀、但找不到同名基名的数量（不删，单独列出） */
    private int orphanSuffixed;

    /** 本次将删除的数量 */
    private int toDelete;
    /** 实际删除的数量 */
    private int deleted;
    /** 数据库里被清理 / 改写的记录数 */
    private int dbRemoved;
    private int dbRewritten;
    private int commits;

    private List<String> samples = new ArrayList<>();
    /** 被跳过的（同名但内容不同）示例 */
    private List<String> diffSamples = new ArrayList<>();
    /** 没有同名基名的 -N 文件示例 */
    private List<String> orphanSamples = new ArrayList<>();
    private String message;
}
