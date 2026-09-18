package com.enterprise.rag.dao.pg;

/** 待入库的父块摘要（摘要树检索用，向量化后存 chunk_summary 表） */
public record SummaryRecord(Long kbId, Long docId, Integer parentIndex, String summary, float[] embedding) {
}
