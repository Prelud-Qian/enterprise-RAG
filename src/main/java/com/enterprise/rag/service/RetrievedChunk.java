package com.enterprise.rag.service;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
    /** 章节路径（标题感知分块：如"员工手册 > 第三章 考勤与休假 > 第五条"） */
    private String headingPath;
    /** BM25 命中的查询词（关键词高亮/可解释性） */
    private final List<String> matchedTerms = new ArrayList<>();

    public RetrievedChunk(Long docId, Integer chunkIndex, String content) {
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }

    public void addScore(double s) {
        this.score += s;
    }
}
