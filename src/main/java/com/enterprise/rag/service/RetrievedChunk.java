package com.enterprise.rag.service;

import lombok.Data;

/**
 * 融合后的检索结果片段（内部模型）
 */
@Data
public class RetrievedChunk {

    private final Long docId;
    private final Integer chunkIndex;
    private String content;
    private String fileName;
    /** RRF 融合得分 */
    private double score;
    /** 向量余弦相似度（仅向量召回命中的片段有值，兜底阈值判断用） */
    private Double vectorSimilarity;
    /** 父级块原文（small-to-big：命中子块后展开为父块喂给 LLM，可空） */
    private String parentContent;

    public RetrievedChunk(Long docId, Integer chunkIndex, String content) {
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public void addScore(double s) {
        this.score += s;
    }
}
