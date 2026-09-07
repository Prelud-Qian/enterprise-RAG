package com.enterprise.rag.dao.pg;

/** 向量召回命中，similarity = 1 - 余弦距离（越大越相似） */
public record VectorHit(Long docId, Integer chunkIndex, String content, Double similarity,
                        String parentContent) {

    /** 无父级块时使用的便捷构造（旧库数据/禁用 small-to-big） */
    public VectorHit(Long docId, Integer chunkIndex, String content, Double similarity) {
        this(docId, chunkIndex, content, similarity, null);
    }
}
