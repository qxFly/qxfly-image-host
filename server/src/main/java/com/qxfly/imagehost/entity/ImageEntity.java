package com.qxfly.imagehost.entity;

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

    /** 1 = 在回收站里（逻辑删除），0 = 正常；回收站里再删一次才是真删 */
    private Integer deleted;

    /** 移入回收站的时间 */
    private LocalDateTime deletedAt;

    /**
     * 回收站目录里的对象键（形如 _trash/api/images/2026/10/13/001/x.png）。
     * 为空表示云端文件还在原位（非 GitHub 仓库，或搬运失败）。
     */
    private String trashKey;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
