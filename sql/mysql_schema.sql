-- =====================================================================
-- 业务库 MySQL 8.0 建表脚本
-- 存放用户、知识库、文档、问答日志；向量数据在 PostgreSQL(pgvector)
-- =====================================================================
CREATE DATABASE IF NOT EXISTS enterprise_rag DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE enterprise_rag;

-- ---------------------------------------------------------------------
-- 1. 用户表（RBAC：role 区分 ADMIN / USER）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(50)     NOT NULL COMMENT '用户名',
    password   VARCHAR(100)    NOT NULL COMMENT 'BCrypt 加密密码',
    role       VARCHAR(20)     NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER',
    enabled    TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0禁用',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 2. 知识库表（数据隔离核心：owner_id 归属用户）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(100)    NOT NULL COMMENT '知识库名称',
    description VARCHAR(500)             DEFAULT NULL COMMENT '描述',
    owner_id    BIGINT UNSIGNED NOT NULL COMMENT '所属用户 id，知识库隔离依据',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库表';

-- ---------------------------------------------------------------------
-- 3. 文档表（元数据；正文片段存 PostgreSQL document_chunk）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    kb_id       BIGINT UNSIGNED NOT NULL COMMENT '所属知识库 id',
    file_name   VARCHAR(255)    NOT NULL COMMENT '原始文件名',
    file_type   VARCHAR(20)     NOT NULL COMMENT '文件类型：pdf/doc/docx',
    file_size   BIGINT          NOT NULL COMMENT '文件大小（字节）',
    chunk_count INT             NOT NULL DEFAULT 0 COMMENT '分块数量',
    status      VARCHAR(20)     NOT NULL DEFAULT 'PARSING' COMMENT '状态：PARSING解析中/READY就绪/FAILED失败',
    error_msg   VARCHAR(500)             DEFAULT NULL COMMENT '失败原因',
    created_by  BIGINT UNSIGNED NOT NULL COMMENT '上传用户 id',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    PRIMARY KEY (id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='文档表';

-- ---------------------------------------------------------------------
-- 4. 问答日志表（审计：提问、回答、检索上下文、来源引用）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS qa_log (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT UNSIGNED NOT NULL COMMENT '提问用户 id',
    kb_id             BIGINT UNSIGNED NOT NULL COMMENT '知识库 id',
    conversation_id   BIGINT UNSIGNED          DEFAULT NULL COMMENT '所属会话 id（多轮对话，单轮为空）',
    question          TEXT            NOT NULL COMMENT '用户提问',
    answer            TEXT            NOT NULL COMMENT '模型回答',
    sources           JSON                     DEFAULT NULL COMMENT '引用来源片段（docId/文件名/片段号/原文）',
    retrieved_context TEXT                     DEFAULT NULL COMMENT '检索到的上下文（Prompt 注入内容）',
    model             VARCHAR(50)              DEFAULT NULL COMMENT '使用的模型',
    is_fallback       TINYINT         NOT NULL DEFAULT 0 COMMENT '是否触发幻觉兜底：1是 0否',
    latency_ms        INT             NOT NULL DEFAULT 0 COMMENT '整体耗时（毫秒）',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    PRIMARY KEY (id),
    KEY idx_user (user_id),
    KEY idx_kb (kb_id),
    KEY idx_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='问答日志表';

-- ---------------------------------------------------------------------
-- 5. 会话表（多轮对话：同一会话的问答共享上下文）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    kb_id      BIGINT UNSIGNED NOT NULL COMMENT '知识库 id',
    user_id    BIGINT UNSIGNED NOT NULL COMMENT '会话归属用户',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_kb_user (kb_id, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话表（多轮对话）';

-- 已建库升级：
-- ALTER TABLE qa_log ADD COLUMN conversation_id BIGINT UNSIGNED DEFAULT NULL COMMENT '所属会话 id';

-- ---------------------------------------------------------------------
-- 初始化管理员：先注册普通用户，再执行下面语句提权
-- UPDATE sys_user SET role = 'ADMIN' WHERE username = '你的用户名';
-- ---------------------------------------------------------------------
