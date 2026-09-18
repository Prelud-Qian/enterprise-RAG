package com.enterprise.rag.dao.pg;

/** 片段引用（构建 BM25 倒排索引的原料） */
public record ChunkRef(Long docId, Integer chunkIndex, String content, String parentContent,
                       String headingPath) {

    public ChunkRef(Long docId, Integer chunkIndex, String content) {
        this(docId, chunkIndex, content, null, null);
    }

    public ChunkRef(Long docId, Integer chunkIndex, String content, String parentContent) {
        this(docId, chunkIndex, content, parentContent, null);
    }
}
