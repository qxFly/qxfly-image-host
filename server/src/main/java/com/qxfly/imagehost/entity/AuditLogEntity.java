package com.qxfly.imagehost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志：谁在什么时候做了什么。写的都是「会产生影响的操作」，读日志本身不记录。
 */
@Data
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String username;

    /** 动作码，见 {@link com.qxfly.imagehost.service.AuditService.Actions} */
    private String action;

    /** 作用对象类型：USER / IMAGE / STORAGE_CONFIG / SETTINGS ... */
    private String targetType;

    private String targetId;

    private String detail;

    /** 1 成功 0 失败（登录失败要留痕） */
    private Integer success;

    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
