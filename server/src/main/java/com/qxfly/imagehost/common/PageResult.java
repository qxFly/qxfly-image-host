package com.qxfly.imagehost.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 统一分页结构。
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    private long total;
    private long page;
    private long size;
    private long pages;
    private List<T> records;

    public static <T> PageResult<T> of(long total, long page, long size, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        result.setPages(size <= 0 ? 0 : (total + size - 1) / size);
        result.setRecords(records);
        return result;
    }
}
