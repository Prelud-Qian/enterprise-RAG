-- =====================================================================
-- 向量库 PostgreSQL + pgvector 建表脚本
-- 前置条件：PostgreSQL 14+，已安装 pgvector 扩展（>=0.5.0 支持 HNSW）
-- 安装方式：CREATE EXTENSION vector; 若报错请先编译安装 pgvector，或使用
--           docker 镜像 pgvector/pgvector:pg16
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- 5. 文档分块片段表（每行一个 Chunk：原文 + BGE-M3 向量 + 溯源元数据）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS document_chunk (
    id             BIGSERIAL PRIMARY KEY,
    kb_id          BIGINT        NOT NULL,   -- 知识库 id（隔离过滤列，检索必须带上）
    doc_id         BIGINT        NOT NULL,   -- 文档 id（对应 MySQL document 表，溯源用）
    chunk_index    INT           NOT NULL,   -- 片段在文档内的序号（从 1 开始）
    content        TEXT          NOT NULL,   -- 片段原文（子块，检索命中的精确定位文本）
    parent_content TEXT,                     -- 父级块原文（small-to-big：喂给 LLM 的完整上下文，可空）
    heading_path   TEXT,                     -- 章节路径（标题感知分块：如"员工手册 > 第三章 考勤与休假 > 第五条"）
    parent_index   INT,                      -- 所属父块序号（摘要树检索用，对应 chunk_summary.parent_index）
    summary        TEXT,                     -- 所属父块的 LLM 摘要（随子块行冗余存储，可空）
    embedding      vector(1024)  NOT NULL,   -- BGE-M3 向量（1024 维）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_doc_chunk UNIQUE (doc_id, chunk_index)
);

-- 父块摘要表（RAPTOR 简化版：先搜摘要定范围，再在范围内精检子块）
CREATE TABLE IF NOT EXISTS chunk_summary (
    id           BIGSERIAL PRIMARY KEY,
    kb_id        BIGINT        NOT NULL,
    doc_id       BIGINT        NOT NULL,
    parent_index INT           NOT NULL,     -- 对应 document_chunk.parent_index
    summary      TEXT          NOT NULL,     -- LLM 生成的父块一句话摘要
    embedding    vector(1024)  NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_summary_parent UNIQUE (doc_id, parent_index)
);

-- HNSW 近似索引 + 余弦距离（pgvector >= 0.5.0）
-- 旧版本 pgvector 请改用：CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON document_chunk (kb_id);
CREATE INDEX IF NOT EXISTS idx_summary_embedding ON chunk_summary USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_summary_kb ON chunk_summary (kb_id);

-- 已建库升级（老库缺列时执行）：
-- ALTER TABLE document_chunk ADD COLUMN parent_content TEXT;
-- ALTER TABLE document_chunk ADD COLUMN heading_path TEXT;
-- ALTER TABLE document_chunk ADD COLUMN parent_index INT;
-- ALTER TABLE document_chunk ADD COLUMN summary TEXT;
-- CREATE TABLE chunk_summary (按上方定义);
