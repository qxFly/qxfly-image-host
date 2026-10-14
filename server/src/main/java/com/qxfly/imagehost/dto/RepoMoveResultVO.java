package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 临时功能：仓库目录整理的结果 / 预览。
 */
@Data
public class RepoMoveResultVO {

    private boolean dryRun;
    /** 实际使用的目录（已做归一化，便于用户核对） */
    private String resolvedSourceDirectory;
    private String resolvedTargetDirectory;
    /** 命中的文件数 */
    private int total;
    /** 实际搬走的文件数 */
    private int moved;
    /** 分批执行时，源目录下还剩多少个没搬（0 = 已清空） */
    private int remaining;
    /** 涉及的提交数 */
    private int commits;
    private String commitSha;
    private String commitUrl;
    /** 新对象键的样子（前若干条） */
    private List<String> samples = new ArrayList<>();
    /** 分片分布，例如 003 -> 500 */
    private List<String> shards = new ArrayList<>();
    /** 因为目标位置已存在同名文件而被跳过的数量（保留原文件名时可能出现） */
    private int collisions;
    private List<String> collisionSamples = new ArrayList<>();
    private String message;
}
