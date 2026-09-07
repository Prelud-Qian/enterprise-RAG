package com.enterprise.rag.service;

import java.util.List;

/**
 * 混合检索结果：TopK 融合片段 + 最佳向量相似度（幻觉兜底阈值判断依据）
 */
public record RetrievalResult(List<RetrievedChunk> chunks, double maxVectorSimilarity) {

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), 0);
    }
}
