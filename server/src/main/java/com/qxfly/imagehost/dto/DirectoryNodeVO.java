package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录树节点。
 */
@Data
public class DirectoryNodeVO {

    /** 完整目录路径，例如 images/2026/10/12/001 */
    private String path;
    /** 末级目录名 */
    private String name;
    /** 该目录下直接存放的文件数 */
    private int count;
    /** 含所有子目录的文件数 */
    private int totalCount;

    private List<DirectoryNodeVO> children = new ArrayList<>();
}
