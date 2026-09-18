package com.enterprise.rag.dao.pg;

/** 摘要召回命中：确定相关父块范围，子块检索在该范围内执行 */
public record SummaryHit(Long docId, Integer parentIndex, String summary, Double similarity) {
}
