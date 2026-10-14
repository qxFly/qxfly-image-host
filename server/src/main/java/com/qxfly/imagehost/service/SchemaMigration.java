package com.qxfly.imagehost.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * 老库结构补齐。
 * <p>
 * 建表脚本用的是 {@code CREATE TABLE IF NOT EXISTS}，对**已经存在**的表不会补新列，
 * 所以升级到「回收站」版本后，由这里把 {@code image_file.deleted / deleted_at} 加上（幂等）。
 * 新建的库由建表脚本直接带这两列，这里会跳过。
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureColumn("image_file", "deleted", "TINYINT NOT NULL DEFAULT 0", "回收站标记");
        ensureColumn("image_file", "deleted_at", "DATETIME DEFAULT NULL", "移入回收站时间");
        ensureColumn("image_file", "trash_key", "VARCHAR(512) DEFAULT NULL", "回收站目录里的对象键");
        ensureColumn("storage_config", "cdn_json", "TEXT DEFAULT NULL", "CDN 列表与默认选择");
    }

    private void ensureColumn(String table, String column, String definition, String description) {
        try {
            if (hasColumn(table, column)) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("已为 {} 补充字段 {}（{}）", table, column, description);
        } catch (Exception e) {
            log.warn("检查/补充字段 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private boolean hasColumn(String table, String column) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((Connection connection) -> {
            DatabaseMetaData meta = connection.getMetaData();
            // 表名大小写在 MySQL / H2 上不一致（MySQL 小写、H2 默认大写），统一忽略大小写比较
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, null, null)) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
            return false;
        }));
    }
}
