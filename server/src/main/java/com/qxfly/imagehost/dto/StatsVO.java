package com.qxfly.imagehost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StatsVO {

    private long totalCount;
    private long totalSize;
    private long todayCount;
    private long storageConfigCount;
    /** 近 7 天新增 */
    private long weekCount;
    /** 最大单张（字节） */
    private long maxSize;
    /** 回收站里的张数与占用 */
    private long trashCount;
    private long trashSize;
    /** 重复图片：组数 + 清掉多余的能省下多少 */
    private long duplicateGroups;
    private long duplicateWastedSize;
    /** 分享链接：个数 + 累计浏览 */
    private long shareCount;
    private long shareViews;
    private List<NameValue> typeDistribution = new ArrayList<>();
    private List<ImageVO> recent = new ArrayList<>();
}
