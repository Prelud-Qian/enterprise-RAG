package com.enterprise.rag.dao.pg;

import java.util.List;

/** BM25 关键词召回命中，score 为 BM25 得分，matchedTerms 为该块命中的查询词（高亮/可解释性） */
public record Bm25Hit(Long docId, Integer chunkIndex, String content, Double score,
                      String parentContent, String headingPath, List<String> matchedTerms) {

    public Bm25Hit(Long docId, Integer chunkIndex, String content, Double score) {
        this(docId, chunkIndex, content, score, null, null, List.of());
    }

    public Bm25Hit(Long docId, Integer chunkIndex, String content, Double score, String parentContent) {
        this(docId, chunkIndex, content, score, parentContent, null, List.of());
    }
}
