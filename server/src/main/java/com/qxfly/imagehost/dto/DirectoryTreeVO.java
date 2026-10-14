package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录树响应：所有目录节点 + 根目录下直接存放的文件数。
 */
@Data
public class DirectoryTreeVO {

    /** 参与统计的文件总数 */
    private int total;
    /** 直接放在根目录（对象键里没有斜杠）的文件数 */
    private int rootFileCount;
    /** 一级目录列表 */
    private List<DirectoryNodeVO> nodes = new ArrayList<>();
}
