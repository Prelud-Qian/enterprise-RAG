package com.enterprise.rag.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 仅检索接口返回（不调 LLM）：调试与评测用 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponse {

    /** 最佳向量相似度（兜底阈值调优/评测参考） */
    private double maxSimilarity;
    /** 检索到的片段（按精排/RRF 得分排序） */
    private List<SourceVO> sources;
}
