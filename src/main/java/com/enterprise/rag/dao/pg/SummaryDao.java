package com.enterprise.rag.dao.pg;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 父块摘要存储（RAPTOR 简化版的摘要层）：chunk_summary 表的读写
 */
@Component
@RequiredArgsConstructor
public class SummaryDao {

    @Qualifier("pgJdbcTemplate")
    private final NamedParameterJdbcTemplate jdbc;

    public void insertBatch(List<SummaryRecord> records) {
        String sql = """
                INSERT INTO chunk_summary (kb_id, doc_id, parent_index, summary, embedding)
                VALUES (:kbId, :docId, :parentIndex, :summary, CAST(:embedding AS vector))
                """;
        List<MapSqlParameterSource> batch = records.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("kbId", r.kbId())
                        .addValue("docId", r.docId())
                        .addValue("parentIndex", r.parentIndex())
                        .addValue("summary", r.summary())
                        .addValue("embedding", toVectorLiteral(r.embedding())))
                .toList();
        jdbc.batchUpdate(sql, batch.toArray(new MapSqlParameterSource[0]));
    }

    /** 摘要召回：查询向量 vs 各父块摘要，TopK 返回 (docId, parentIndex) 范围 */
    public List<SummaryHit> searchByKb(Long kbId, float[] queryVector, int topK) {
        String sql = """
                SELECT doc_id, parent_index, summary,
                       1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM chunk_summary
                WHERE kb_id = :kbId
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbId", kbId)
                .addValue("embedding", toVectorLiteral(queryVector))
                .addValue("topK", topK);
        return jdbc.query(sql, params, (rs, n) -> new SummaryHit(
                rs.getLong("doc_id"),
                rs.getInt("parent_index"),
                rs.getString("summary"),
                rs.getDouble("similarity")));
    }

    public void deleteByDocId(Long docId) {
        jdbc.update("DELETE FROM chunk_summary WHERE doc_id = :docId",
                new MapSqlParameterSource("docId", docId));
    }

    public void deleteByKbId(Long kbId) {
        jdbc.update("DELETE FROM chunk_summary WHERE kb_id = :kbId",
                new MapSqlParameterSource("kbId", kbId));
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 9);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return sb.append(']').toString();
    }
}
