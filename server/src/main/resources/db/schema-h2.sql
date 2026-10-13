-- =============================================================
-- PicHost - H2 初始化脚本（仅用于 profile=h2 的本地冒烟启动）
-- 与 schema-mysql.sql 结构保持一致，省略 MySQL 专有语法
-- =============================================================

CREATE TABLE IF NOT EXISTS sys_user
(
    id         BIGINT AUTO_INCREMENT NOT NULL,
    username   VARCHAR(64)  NOT NULL,
    password   VARCHAR(128) NOT NULL,
    nickname   VARCHAR(64)  DEFAULT NULL,
    email      VARCHAR(128) DEFAULT NULL,
    role       VARCHAR(32)  NOT NULL DEFAULT 'USER',
    status     TINYINT      NOT NULL DEFAULT 1,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS storage_config
(
    id          BIGINT AUTO_INCREMENT NOT NULL,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    config_json TEXT         NOT NULL,
    is_default  TINYINT      NOT NULL DEFAULT 0,
    enabled     TINYINT      NOT NULL DEFAULT 1,
    remark      VARCHAR(255) DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_storage_user_name UNIQUE (user_id, name)
);

CREATE TABLE IF NOT EXISTS image_file
(
    id                BIGINT AUTO_INCREMENT NOT NULL,
    user_id           BIGINT        NOT NULL,
    storage_config_id BIGINT        NOT NULL,
    storage_type      VARCHAR(32)   NOT NULL,
    original_name     VARCHAR(255)  DEFAULT NULL,
    object_key        VARCHAR(512)  NOT NULL,
    url               VARCHAR(1024) NOT NULL,
    thumb_url         VARCHAR(1024) DEFAULT NULL,
    mime_type         VARCHAR(128)  DEFAULT NULL,
    size              BIGINT        NOT NULL DEFAULT 0,
    hash              VARCHAR(64)   DEFAULT NULL,
    width             INT           DEFAULT NULL,
    height            INT           DEFAULT NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
