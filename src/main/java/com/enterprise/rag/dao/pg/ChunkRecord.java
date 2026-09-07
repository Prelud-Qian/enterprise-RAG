package com.enterprise.rag.dao.pg;

/** 待入库片段（原文 + 向量 + 父级块，small-to-big） */
public record ChunkRecord(Long kbId, Long docId, Integer chunkIndex, String content,
                          float[] embedding, String parentContent) {
}
