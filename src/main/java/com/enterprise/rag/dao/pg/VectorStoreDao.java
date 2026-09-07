package com.enterprise.rag.dao.pg;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * pgvector 向量存储 DAO（手写 SQL，不用框架封装，理由见 README「技术选型对比」）
 * 核心：所有查询必须带 kb_id 过滤，保证知识库隔离在存储层兜底
 */
@Component
@RequiredArgsConstructor
public class VectorStoreDao {

    @Qualifier("pgJdbcTemplate")
    private final NamedParameterJdbcTemplate jdbc;

    /** 批量写入片段向量（含父级块 parent_content，可空） */
    public void insertBatch(List<ChunkRecord> chunks) {
        String sql = """
                INSERT INTO document_chunk (kb_id, doc_id, chunk_index, content, parent_content, embedding)
                VALUES (:kbId, :docId, :chunkIndex, :content, :parentContent, CAST(:embedding AS vector))
                """;
        List<MapSqlParameterSource> batch = chunks.stream()
                .map(c -> new MapSqlParameterSource()
                        .addValue("kbId", c.kbId())
                        .addValue("docId", c.docId())
                        .addValue("chunkIndex", c.chunkIndex())
                        .addValue("content", c.content())
                        .addValue("parentContent", c.parentContent())
                        .addValue("embedding", toVectorLiteral(c.embedding())))
                .toList();
        // 直接传数组：SqlParameterSourceUtils.createBatch 会把 MapSqlParameterSource
        // 当 JavaBean 反射（Bean property 'kbId' not readable），Spring 6.1 的坑
        jdbc.batchUpdate(sql, batch.toArray(new MapSqlParameterSource[0]));
    }

    /**
     * 向量相似度检索：<=> 是 pgvector 的余弦距离运算符，
     * 1 - 距离 = 余弦相似度，越大越相关，按距离升序取 TopK
     */
    public List<VectorHit> searchByKb(Long kbId, float[] queryVector, int topK) {
        String sql = """
                SELECT c.doc_id, c.chunk_index, c.content, c.parent_content,
                       1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM document_chunk c
                WHERE c.kb_id = :kbId
                ORDER BY c.embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbId", kbId)
                .addValue("embedding", toVectorLiteral(queryVector))
                .addValue("topK", topK);
        return jdbc.query(sql, params, (rs, n) -> new VectorHit(
                rs.getLong("doc_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getDouble("similarity"),
                rs.getString("parent_content")));
    }

    /** 加载某知识库全部片段（构建 BM25 索引用） */
    public List<ChunkRef> loadChunksByKb(Long kbId) {
        String sql = "SELECT doc_id, chunk_index, content, parent_content FROM document_chunk WHERE kb_id = :kbId ORDER BY doc_id, chunk_index";
        return jdbc.query(sql, new MapSqlParameterSource("kbId", kbId), (rs, n) -> new ChunkRef(
                rs.getLong("doc_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getString("parent_content")));
    }

    public void deleteByDocId(Long docId) {
        jdbc.update("DELETE FROM document_chunk WHERE doc_id = :docId",
                new MapSqlParameterSource("docId", docId));
    }

    public void deleteByKbId(Long kbId) {
        jdbc.update("DELETE FROM document_chunk WHERE kb_id = :kbId",
                new MapSqlParameterSource("kbId", kbId));
    }

    public long countByKb(Long kbId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE kb_id = :kbId",
                new MapSqlParameterSource("kbId", kbId), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * float[] 转 pgvector 字面量 '[x,y,z]'。
     * 保留 6 位小数：精度足够（余弦对低权重位不敏感），同时比完整浮点序列化省约一半体积
     */
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
