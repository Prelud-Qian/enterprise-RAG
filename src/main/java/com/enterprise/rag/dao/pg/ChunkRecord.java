package com.enterprise.rag.dao.pg;

/** 待入库片段（原文 + 向量 + 父块 + 章节路径 + 父块摘要，small-to-big + 标题感知 + 摘要树） */
public record ChunkRecord(Long kbId, Long docId, Integer chunkIndex, String content,
                          float[] embedding, String parentContent, String headingPath,
                          Integer parentIndex, String summary) {

    public ChunkRecord(Long kbId, Long docId, Integer chunkIndex, String content,
                       float[] embedding, String parentContent) {
        this(kbId, docId, chunkIndex, content, embedding, parentContent, null, null, null);
    }

    public ChunkRecord(Long kbId, Long docId, Integer chunkIndex, String content, float[] embedding) {
        this(kbId, docId, chunkIndex, content, embedding, null, null, null, null);
    }
}
