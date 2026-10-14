package com.qxfly.imagehost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统设置（key-value）。目前只有一个键：单张文件大小上限。
 */
@Data
@TableName("app_setting")
public class AppSettingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String settingKey;

    private String settingValue;

    private LocalDateTime updatedAt;
}
