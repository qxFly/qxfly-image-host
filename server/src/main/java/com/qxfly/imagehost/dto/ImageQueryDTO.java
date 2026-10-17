package com.qxfly.imagehost.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 图库列表查询条件。
 * <p>
 * 新增筛选维度时往这里加字段即可：service 侧用「条件 + 值」的方式接到 query wrapper 上，
 * 前端接口直接传同名查询参数。
 */
@Data
public class ImageQueryDTO {

    private long page = 1;
    private long size = 30;
    /** 关键字：文件名 / 对象键 */
    private String keyword;
    /** 关键字匹配方式：fuzzy（默认）| exact | prefix */
    private String matchMode;
    /** 只看某个存储后端 */
    private Long storageConfigId;
    /** 目录前缀；__root__ 表示只看根目录下的文件 */
    private String directory;

    /** 体积区间（字节） */
    private Long minSize;
    private Long maxSize;
    /** 上传日期区间（含当天），格式 yyyy-MM-dd */
    private String dateFrom;
    private String dateTo;
    /** 尺寸区间（像素） */
    private Integer minWidth;
    private Integer maxWidth;
    private Integer minHeight;
    private Integer maxHeight;

    /** 开始时间（起始日 00:00），日期无法解析时返回 null */
    public java.time.LocalDateTime createdAtFrom() {
        return parseDate(dateFrom, false);
    }

    /** 结束时间（结束日的下一天 00:00，左闭右开），无法解析时返回 null */
    public java.time.LocalDateTime createdAtTo() {
        return parseDate(dateTo, true);
    }

    private java.time.LocalDateTime parseDate(String value, boolean endOfDay) {
        if (!org.springframework.util.StringUtils.hasText(value)) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value.trim());
            return endOfDay ? date.plusDays(1).atStartOfDay() : date.atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }
}
