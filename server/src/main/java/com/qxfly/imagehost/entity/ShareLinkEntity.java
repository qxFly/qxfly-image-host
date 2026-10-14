package com.qxfly.imagehost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分享链接（相册）：把选中的图片打包成一个可对外访问的链接。
 */
@Data
@TableName("share_link")
public class ShareLinkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 随机 token，出现在对外地址里 */
    private String token;

    private String title;

    /** 访问密码的 BCrypt 哈希，为空表示不需要密码 */
    private String passwordHash;

    /** 过期时间，为空表示长期有效 */
    private LocalDateTime expiresAt;

    private Integer enabled;

    private Long viewCount;

    /** 图片 id 列表（JSON 数组），相册是有序的 */
    private String imageIds;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
