-- =============================================================
-- qxfly-image-host 图床管理 - MySQL 8 初始化脚本（幂等，可重复执行）
-- 应用启动时由 spring.sql.init 自动执行
-- =============================================================

CREATE TABLE IF NOT EXISTS sys_user
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL,
    password   VARCHAR(128) NOT NULL,
    nickname   VARCHAR(64)           DEFAULT NULL,
    email      VARCHAR(128)          DEFAULT NULL,
    role       VARCHAR(32)  NOT NULL DEFAULT 'USER',
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

-- 分享链接（相册）：把选中的图片打包成一个可对外访问的链接，支持有效期 / 访问密码 / 随时撤销
CREATE TABLE IF NOT EXISTS share_link
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    token         VARCHAR(64)  NOT NULL,
    title         VARCHAR(128)          DEFAULT NULL,
    password_hash VARCHAR(128)          DEFAULT NULL,
    expires_at    DATETIME              DEFAULT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    view_count    BIGINT       NOT NULL DEFAULT 0,
    image_ids     TEXT         NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_share_token UNIQUE (token),
    INDEX idx_share_user (user_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

-- 审计日志：谁在什么时候做了什么（登录、上传、删除、改配置、改账号、改设置……）
CREATE TABLE IF NOT EXISTS audit_log
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT                DEFAULT NULL,
    username    VARCHAR(64)           DEFAULT NULL,
    action      VARCHAR(48)  NOT NULL,
    target_type VARCHAR(32)           DEFAULT NULL,
    target_id   VARCHAR(128)          DEFAULT NULL,
    detail      VARCHAR(512)          DEFAULT NULL,
    success     TINYINT      NOT NULL DEFAULT 1,
    ip          VARCHAR(64)           DEFAULT NULL,
    user_agent  VARCHAR(255)          DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_audit_user (user_id, id),
    INDEX idx_audit_action (action, id),
    INDEX idx_audit_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

-- 系统设置（key-value）：目前用于「单张文件大小上限」等可在界面上改的运行时配置
CREATE TABLE IF NOT EXISTS app_setting
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(64)  NOT NULL,
    setting_value VARCHAR(512) NOT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_setting_key UNIQUE (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS storage_config
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     BIGINT      NOT NULL,
    name        VARCHAR(64) NOT NULL,
    type        VARCHAR(32) NOT NULL,
    config_json TEXT        NOT NULL,
    is_default  TINYINT     NOT NULL DEFAULT 0,
    enabled     TINYINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(255)         DEFAULT NULL,
    -- CDN 列表与默认选择（JSON：自定义项 + 预览/下载/复制默认）
    cdn_json    TEXT                 DEFAULT NULL,
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_storage_user_name UNIQUE (user_id, name),
    INDEX idx_storage_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS image_file
(
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_id           BIGINT        NOT NULL,
    storage_config_id BIGINT        NOT NULL,
    storage_type      VARCHAR(32)   NOT NULL,
    original_name     VARCHAR(255)           DEFAULT NULL,
    object_key        VARCHAR(512)  NOT NULL,
    url               VARCHAR(1024) NOT NULL,
    thumb_url         VARCHAR(1024)          DEFAULT NULL,
    mime_type         VARCHAR(128)           DEFAULT NULL,
    size              BIGINT        NOT NULL DEFAULT 0,
    hash              VARCHAR(64)            DEFAULT NULL,
    width             INT                    DEFAULT NULL,
    height            INT                    DEFAULT NULL,
    -- 逻辑删除：1 = 在回收站里，0 = 正常；回收站里再删才是物理删除
    deleted           TINYINT       NOT NULL DEFAULT 0,
    deleted_at        DATETIME               DEFAULT NULL,
    -- 回收站目录里的对象键（GitHub 仓库会把文件搬到 _trash/ 下，恢复时再搬回）
    trash_key         VARCHAR(512)           DEFAULT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_image_user (user_id, id),
    INDEX idx_image_hash (user_id, hash),
    INDEX idx_image_storage (storage_config_id),
    INDEX idx_image_deleted (user_id, deleted, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
