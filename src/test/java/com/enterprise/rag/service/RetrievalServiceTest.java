package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.DocumentMapper;
import com.enterprise.rag.dao.pg.Bm25Hit;
import com.enterprise.rag.dao.pg.VectorHit;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.entity.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorStoreDao vectorStoreDao;
    @Mock
    private Bm25IndexService bm25IndexService;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private RerankService rerankService;
    @Mock
    private QueryRewriteService queryRewriteService;

    private RetrievalService service;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        service = new RetrievalService(embeddingService, vectorStoreDao,
                bm25IndexService, documentMapper, props, rerankService, queryRewriteService);
        // 默认不做改写（改写行为单独用例覆盖），保持原查询
        when(queryRewriteService.rewrite(anyString()))
                .thenAnswer(inv -> List.of(inv.getArgument(0, String.class)));
    }

    @Test
    @DisplayName("RRF 融合：两边都命中的片段排名最高，单边命中按排名计分")
    void rrf融合排序() {
        // 测试中让精排保持 RRF 顺序（精排逻辑由 RerankService 自身测试覆盖）
        when(rerankService.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> candidates = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        // 向量召回: A(rank0), B(rank1)；BM25 召回: B(rank0), C(rank1)
        when(embeddingService.embed("测试问题")).thenReturn(new float[1024]);
        when(vectorStoreDao.searchByKb(eq(1L), any(), eq(10))).thenReturn(List.of(
                new VectorHit(1L, 1, "A片段", 0.9),
                new VectorHit(2L, 1, "B片段", 0.8)));
        when(bm25IndexService.search(eq(1L), eq("测试问题"), eq(10))).thenReturn(List.of(
                new Bm25Hit(2L, 1, "B片段", 2.0),
                new Bm25Hit(3L, 1, "C片段", 1.0)));
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                doc(1L, "文件A"), doc(2L, "文件B"), doc(3L, "文件C")));

        RetrievalResult result = service.retrieve(1L, "测试问题");

        // B 的 RRF 分 = 1/61 + 1/61 > A = 1/61 > C = 1/62 → 顺序 B, A, C
        assertEquals(List.of(2L, 1L, 3L),
                result.chunks().stream().map(RetrievedChunk::getDocId).toList());
        assertEquals("文件B", result.chunks().get(0).getFileName());
        assertEquals(0.9, result.maxVectorSimilarity());
    }

    @Test
    @DisplayName("召回为空：两路都无结果时返回空，不触发精排")
    void 空召回() {
        when(embeddingService.embed("测试问题")).thenReturn(new float[1024]);
        when(vectorStoreDao.searchByKb(eq(1L), any(), eq(10))).thenReturn(List.of());
        when(bm25IndexService.search(eq(1L), eq("测试问题"), eq(10))).thenReturn(List.of());

        RetrievalResult result = service.retrieve(1L, "测试问题");

        assertTrue(result.isEmpty());
        verify(rerankService, never()).rerank(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("多查询改写：多个查询的 RRF 得分在同一片段上累积")
    void 多查询改写合并() {
        when(rerankService.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> candidates = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        // 改写出两个查询
        when(queryRewriteService.rewrite("测试问题")).thenReturn(List.of("测试问题", "改写查询2"));
        when(embeddingService.embed("测试问题")).thenReturn(new float[1024]);
        when(embeddingService.embed("改写查询2")).thenReturn(new float[1024]);
        // 查询1: 向量命中 A；查询2: 向量命中 B
        when(vectorStoreDao.searchByKb(eq(1L), any(), eq(10)))
                .thenReturn(List.of(new VectorHit(1L, 1, "A片段", 0.9)))
                .thenReturn(List.of(new VectorHit(2L, 1, "B片段", 0.85)));
        // 查询1: BM25 也命中 B（B 在两个列表都有排名）
        when(bm25IndexService.search(eq(1L), anyString(), eq(10)))
                .thenReturn(List.of(new Bm25Hit(2L, 1, "B片段", 2.0)))
                .thenReturn(List.of());
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                doc(1L, "文件A"), doc(2L, "文件B")));

        RetrievalResult result = service.retrieve(1L, "测试问题");

        // B = 1/61(查询1 BM25) + 1/61(查询2 向量) > A = 1/61(查询1 向量) → B 排第一
        assertEquals(List.of(2L, 1L),
                result.chunks().stream().map(RetrievedChunk::getDocId).toList());
        // maxSimilarity 取所有查询中的最大值
        assertEquals(0.9, result.maxVectorSimilarity());
    }

    @Test
    @DisplayName("父级块展开：同父块的子块去重后返回父块内容，独立子块保留自身")
    void 父块展开() {
        when(rerankService.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> candidates = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        when(embeddingService.embed("测试问题")).thenReturn(new float[1024]);
        String parent = "父级块完整内容";
        when(vectorStoreDao.searchByKb(eq(1L), any(), eq(10))).thenReturn(List.of(
                new VectorHit(1L, 1, "子块一", 0.9, parent),
                new VectorHit(1L, 2, "子块二", 0.8, parent),
                new VectorHit(2L, 1, "独立子块", 0.7)));
        when(bm25IndexService.search(eq(1L), eq("测试问题"), eq(10))).thenReturn(List.of());
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                doc(1L, "文件A"), doc(2L, "文件B")));

        RetrievalResult result = service.retrieve(1L, "测试问题");

        // 子块一/二 同父块 → 去重为一条父块内容；独立子块保留自身
        assertEquals(2, result.chunks().size());
        assertEquals(parent, result.chunks().get(0).getContent());
        assertEquals(1, result.chunks().get(0).getChunkIndex());   // 保留得分最高子块的溯源
        assertEquals("独立子块", result.chunks().get(1).getContent());
    }

    private Document doc(Long id, String fileName) {
        Document d = new Document();
        d.setId(id);
        d.setFileName(fileName);
        return d;
    }
}
