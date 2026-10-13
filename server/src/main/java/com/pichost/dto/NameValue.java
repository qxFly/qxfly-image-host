package com.pichost.dto;

/**
 * 通用名称-数值对（用于统计图表）。
 */
public record NameValue(String name, String label, long value) {
}
