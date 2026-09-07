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
    embedding      vector(1024)  NOT NULL,   -- BGE-M3 向量（1024 维）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_doc_chunk UNIQUE (doc_id, chunk_index)
);

-- HNSW 近似索引 + 余弦距离（pgvector >= 0.5.0）
-- 旧版本 pgvector 请改用：CREATE INDEX ... USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON document_chunk (kb_id);

-- 已建库升级（老库没有 parent_content 列时执行）：
-- ALTER TABLE document_chunk ADD COLUMN parent_content TEXT;
