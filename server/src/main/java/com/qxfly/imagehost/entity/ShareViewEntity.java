package com.qxfly.imagehost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接的访问明细：谁在什么时候打开过这个相册。
 */
@Data
@TableName("share_view")
public class ShareViewEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long shareId;

    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
