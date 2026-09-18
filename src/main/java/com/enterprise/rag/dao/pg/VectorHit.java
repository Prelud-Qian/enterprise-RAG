package com.enterprise.rag.dao.pg;

/** 向量召回命中，similarity = 1 - 余弦距离（越大越相似） */
public record VectorHit(Long docId, Integer chunkIndex, String content, Double similarity,
                        String parentContent, String headingPath) {

    public VectorHit(Long docId, Integer chunkIndex, String content, Double similarity) {
        this(docId, chunkIndex, content, similarity, null, null);
    }

    public VectorHit(Long docId, Integer chunkIndex, String content, Double similarity, String parentContent) {
        this(docId, chunkIndex, content, similarity, parentContent, null);
    }
}
