package com.pichost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 图片元数据。
 */
@Data
@TableName("image_file")
public class ImageEntity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long storageConfigId;

    private String storageType;

    private String originalName;

    /** 存储后端中的对象键 */
    private String objectKey;

    private String url;

    private String thumbUrl;

    private String mimeType;

    private Long size;

    /** 内容 sha256，用于同用户去重 */
    private String hash;

    private Integer width;

    private Integer height;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
