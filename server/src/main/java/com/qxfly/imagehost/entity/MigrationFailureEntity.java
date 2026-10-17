package com.qxfly.imagehost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 迁移失败项：持久化下来，重启不丢，可在「后台任务」面板一键重试。
 */
@Data
@TableName("migration_failure")
public class MigrationFailureEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long imageId;

    private Long toConfigId;

    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
