package com.enterprise.rag.dao.pg;

/** BM25 关键词召回命中，score 为 BM25 得分 */
public record Bm25Hit(Long docId, Integer chunkIndex, String content, Double score,
                      String parentContent) {

    public Bm25Hit(Long docId, Integer chunkIndex, String content, Double score) {
        this(docId, chunkIndex, content, score, null);
    }
}
