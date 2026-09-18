package com.enterprise.rag.service;

import com.enterprise.rag.dao.pg.Bm25Hit;
import com.enterprise.rag.dao.pg.ChunkRef;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.util.JiebaUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Bm25IndexServiceTest {

    @Mock
    private VectorStoreDao vectorStoreDao;

    private Bm25IndexService service;

    @BeforeEach
    void setUp() {
        service = new Bm25IndexService(vectorStoreDao);
    }

    @Test
    @DisplayName("关键词命中：包含查询词的片段得分最高")
    void 关键词命中() {
        when(vectorStoreDao.loadChunksByKb(1L)).thenReturn(List.of(
                new ChunkRef(10L, 1, "员工年假制度说明"),
                new ChunkRef(10L, 2, "差旅报销流程指南")));

        List<Bm25Hit> hits = service.search(1L, "年假怎么休", 3);

        assertFalse(hits.isEmpty());
        assertEquals(10L, hits.get(0).docId());
        assertEquals(1, hits.get(0).chunkIndex());
        assertTrue(hits.get(0).score() > 0);
        // 命中词记录：该块的命中词非空且是查询分词后的子集（关键词高亮用）
        assertFalse(hits.get(0).matchedTerms().isEmpty());
        assertTrue(JiebaUtil.tokenize("年假怎么休").containsAll(hits.get(0).matchedTerms()));
    }

    @Test
    @DisplayName("无命中：查询词不在任何片段中时返回空")
    void 无命中() {
        when(vectorStoreDao.loadChunksByKb(1L)).thenReturn(List.of(
                new ChunkRef(10L, 1, "员工年假制度说明"),
                new ChunkRef(10L, 2, "差旅报销流程指南")));

        assertTrue(service.search(1L, "量子力学", 3).isEmpty());
    }

    @Test
    @DisplayName("重建：文档变更后下次查询重新加载片段")
    void 重建() {
        when(vectorStoreDao.loadChunksByKb(1L)).thenReturn(List.of(
                new ChunkRef(10L, 1, "员工年假制度说明")));

        service.search(1L, "年假", 3);
        service.rebuild(1L);
        service.search(1L, "年假", 3);

        verify(vectorStoreDao, times(2)).loadChunksByKb(1L);
    }

    @Test
    @DisplayName("空知识库：无片段时不报错返回空")
    void 空索引() {
        when(vectorStoreDao.loadChunksByKb(1L)).thenReturn(List.of());
        assertTrue(service.search(1L, "任意问题", 3).isEmpty());
    }
}
