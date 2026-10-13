package com.pichost.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StatsVO {

    private long totalCount;
    private long totalSize;
    private long todayCount;
    private long storageConfigCount;
    private List<NameValue> typeDistribution = new ArrayList<>();
    private List<ImageVO> recent = new ArrayList<>();
}
