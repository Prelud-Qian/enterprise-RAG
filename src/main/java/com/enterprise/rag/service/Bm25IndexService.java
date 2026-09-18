package com.enterprise.rag.service;

import com.enterprise.rag.dao.pg.Bm25Hit;
import com.enterprise.rag.dao.pg.ChunkRef;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.util.JiebaUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BM25 关键词检索：内存倒排索引实现（零额外中间件）
 *
 * 结构：每个知识库一份索引快照 KbIndex
 * - docs:    片段列表（下标即内部 chunkId）
 * - postings: term → (chunkId → 词频 tf)
 * - docLen/avgDocLen: 文档长度（BM25 长度归一化用）
 *
 * 策略：索引懒加载（首次查询时构建），文档增删后整体重建（简单可靠）。
 * 数据量级 > 10 万 chunk 时应演进为增量索引或迁移 ES/pg_search（见 README）。
 *
 * BM25 公式：score = Σ idf(term) * (tf*(k1+1)) / (tf + k1*(1-b+b*dl/avgdl))
 * k1=1.5 控制词频饱和，b=0.75 控制文档长度归一化强度
 */
@Service
@RequiredArgsConstructor
public class Bm25IndexService {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final VectorStoreDao vectorStoreDao;
    private final ConcurrentHashMap<Long, KbIndex> indexes = new ConcurrentHashMap<>();

    /** 文档变更后调用：移除旧索引，下次查询懒重建 */
    public synchronized void rebuild(Long kbId) {
        indexes.remove(kbId);
    }

    /** BM25 检索：返回 TopK 命中（score 越大越相关），并记录每块的命中词（高亮/可解释性） */
    public synchronized List<Bm25Hit> search(Long kbId, String query, int topK) {
        KbIndex index = indexes.computeIfAbsent(kbId, this::build);
        if (index.docs().isEmpty()) {
            return List.of();
        }

        List<String> terms = JiebaUtil.tokenize(query);
        double[] scores = new double[index.docs().size()];
        Map<Integer, List<String>> matchedTerms = new HashMap<>();

        // 对查询的每个词累加 BM25 得分，并记录命中词
        for (String term : terms) {
            Map<Integer, Integer> postings = index.postings().get(term);
            if (postings == null) {
                continue;
            }
            int df = postings.size();
            double idf = Math.log(1 + (index.docs().size() - df + 0.5) / (df + 0.5));
            for (Map.Entry<Integer, Integer> entry : postings.entrySet()) {
                int i = entry.getKey();
                int tf = entry.getValue();
                double dl = index.docLen()[i];
                scores[i] += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / index.avgDocLen()));
                matchedTerms.computeIfAbsent(i, k -> new ArrayList<>()).add(term);
            }
        }

        // 最小堆取 TopK
        PriorityQueue<Bm25Hit> pq = new PriorityQueue<>(Comparator.comparingDouble(Bm25Hit::score));
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] <= 0) {
                continue;
            }
            ChunkRef ref = index.docs().get(i);
            pq.offer(new Bm25Hit(ref.docId(), ref.chunkIndex(), ref.content(), scores[i],
                    ref.parentContent(), ref.headingPath(), matchedTerms.getOrDefault(i, List.of())));
            if (pq.size() > topK) {
                pq.poll();
            }
        }
        List<Bm25Hit> hits = new ArrayList<>(pq);
        hits.sort(Comparator.comparingDouble(Bm25Hit::score).reversed());
        return hits;
    }

    /** 全量构建某知识库的倒排索引 */
    private KbIndex build(Long kbId) {
        List<ChunkRef> chunks = vectorStoreDao.loadChunksByKb(kbId);
        Map<String, Map<Integer, Integer>> postings = new HashMap<>();
        double[] docLen = new double[chunks.size()];
        long totalLen = 0;
        for (int i = 0; i < chunks.size(); i++) {
            for (String term : JiebaUtil.tokenize(chunks.get(i).content())) {
                postings.computeIfAbsent(term, t -> new HashMap<>()).merge(i, 1, Integer::sum);
            }
            docLen[i] = chunks.get(i).content().length();
            totalLen += docLen[i];
        }
        double avgDocLen = chunks.isEmpty() ? 1 : (double) totalLen / chunks.size();
        return new KbIndex(chunks, postings, docLen, avgDocLen);
    }

    private record KbIndex(List<ChunkRef> docs,
                           Map<String, Map<Integer, Integer>> postings,
                           double[] docLen,
                           double avgDocLen) {
    }
}
