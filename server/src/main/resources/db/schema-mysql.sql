-- =============================================================
-- PicHost 图床管理 - MySQL 8 初始化脚本（幂等，可重复执行）
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
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_image_user (user_id, id),
    INDEX idx_image_hash (user_id, hash),
    INDEX idx_image_storage (storage_config_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci;
