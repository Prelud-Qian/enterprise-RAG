package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.DocumentMapper;
import com.enterprise.rag.dao.pg.Bm25Hit;
import com.enterprise.rag.dao.pg.SummaryDao;
import com.enterprise.rag.dao.pg.SummaryHit;
import com.enterprise.rag.dao.pg.VectorHit;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.entity.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 混合检索（Hybrid Retrieval）：向量相似度检索 + BM25 关键词检索，RRF 融合
 *
 * 为什么混合：向量检索擅长语义相近（"怎么涨工资"≈"调薪制度"），
 * 但漏掉精确关键词（人名、工号、专有名词）；BM25 正好互补。
 * 两类得分量纲完全不同（余弦相似度 0~1 vs BM25 无上界），
 * 直接加权相加不可靠，所以用 RRF（Reciprocal Rank Fusion）按排名融合：
 * score = Σ 1/(k + rank)，k=60，只依赖排序位置，对量纲不敏感。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorStoreDao vectorStoreDao;
    private final Bm25IndexService bm25IndexService;
    private final DocumentMapper documentMapper;
    private final RagProperties props;
    private final RerankService rerankService;
    private final QueryRewriteService queryRewriteService;
    private final SummaryDao summaryDao;

    /**
     * query ─┬─ ⓪ 改写 → [q1, q2, q3]  （1 个问题变 3 个）
     *        │
     *        └─ ① 对每个 q，两条路并行召回，结果边召回边融合进同一个 Map
     *               ├─ 向量路：摘要定范围 → 范围内查子块
     *               └─ BM25 路：内存倒排表打分
     *                     ↓  RRF 按排名折算分数，跨查询累加
     *        ② 按 RRF 分粗排，取 topN=20 候选
     *        ③ Rerank 精排 → 截到 finalTopK=5
     *        ④ 父块展开（子块换父块，同父块去重）
     *        ⑤ 回填文件名（查 MySQL）
     *        ⑥ 返回
     */
    public RetrievalResult retrieve(Long kbId, String query) {
        // 从 props 配置对象中取出"检索（Retrieval）"这部分配置，赋值给变量 r，后续可以通过 r 访问具体的检索参数
        RagProperties.Retrieval r = props.getRetrieval();

        // 【混合检索-1】Query 改写：原始问题 + LLM 生成的多个检索查询（多查询检索）
        List<String> queries = queryRewriteService.rewrite(query);

        // 【混合检索-2/3】对每个查询独立做 向量召回(pgvector <=> 余弦) + BM25 召回，
        // 同一片段可被多个查询的排名列表命中，RRF 得分跨查询累积（多查询 RRF）
        Map<ChunkKey, RetrievedChunk> merged = new LinkedHashMap<>();
        double maxSimilarity = 0; // 累加器，记录整轮检索中向量相似度的峰值
        int rrfK = r.getRrfK(); // 从配置里取出 RRF 的 k 值
        for (String q : queries) {
            // 把这条问题文本进行向量化
            float[] queryVector = embeddingService.embed(q);

            // 摘要树检索（RAPTOR 简化）：先搜父块摘要定相关范围，子块向量检索只在该范围内执行；
            // 摘要表为空/召回为空时自动降级为全库检索
            List<VectorHit> vectorHits; // 声明一个装"向量检索结果"的盒子
            // 看看配置里"摘要检索"这个开关打开了没
            if (props.getSummary().getEnabled()) {
                // 拿刚才那个向量去摘要表里搜，找出最像的 3 个父块
                List<SummaryHit> summaryHits = summaryDao.searchByKb(kbId, queryVector, props.getSummary().getTopN());
                if (!summaryHits.isEmpty()) {
                    //  把这 3 个父块转成 3 个"范围"（哪个文档的第几个父块），等下就用它圈定搜索地盘
                    List<VectorStoreDao.Scope> scopes = summaryHits.stream()
                            .map(h -> new VectorStoreDao.Scope(h.docId(), h.parentIndex()))
                            .distinct()
                            .toList();
                    // 在刚圈定的 3 个范围里，用同一个向量搜子块，取最像的 10 个
                    vectorHits = vectorStoreDao.searchByKb(kbId, queryVector, r.getVectorTopK(), scopes);
                    log.debug("摘要召回 {} 个父块范围，范围内子块检索命中 {}", scopes.size(), vectorHits.size());
                } else {
                    // 摘要一条都没搜到的话，就不圈范围了，退回全库搜，同样取 10 条
                    vectorHits = vectorStoreDao.searchByKb(kbId, queryVector, r.getVectorTopK());
                }
            } else {
                // 摘要功能压根没开的话，也是全库搜 10 条
                vectorHits = vectorStoreDao.searchByKb(kbId, queryVector, r.getVectorTopK());
            }

            /**
             * 此时 vectorHits 中 每个子块 已经按照 向量相似度 进行 降序排列（SQL ORDER BY 保证）
             * 接着独立执行 BM25 关键词召回：在全库范围内对每个子块按查询词打分，
             * 得到 bm25Hits；两条路的召回结果稍后合并进 merged 做 RRF 融合
             */

            // 拿查询 q 去内存倒排表里打分，取前 10 条  降序（分数从高到低）
            List<Bm25Hit> bm25Hits = bm25IndexService.search(kbId, q, r.getBm25TopK());
            // 向量检索结果 不为空
            if (!vectorHits.isEmpty()) {
                // 把本轮向量第 1 名的相似度记下来，跟历史峰值比，只留大的
                maxSimilarity = Math.max(maxSimilarity, vectorHits.get(0).similarity());
            }

            for (int i = 0; i < vectorHits.size(); i++) {
                VectorHit h = vectorHits.get(i);
                /**
                 * 同一个块在 merged 里只会有一条
                 * 单个 for 循环的一次遍历中，不可能出现两个相同的块
                 *
                 * computeIfAbsent 用 (docId, chunkIndex) 查 merged：查到就返回已有的，
                 * 查不到就新建一个放进去再返回。两种情况都返回一个 RetrievedChunk 赋值给 chunk。
                 */
                RetrievedChunk chunk = merged.computeIfAbsent(new ChunkKey(h.docId(), h.chunkIndex()),
                        k -> new RetrievedChunk(h.docId(), h.chunkIndex(), h.content()));
                /**
                 * chunk 是 merged 里那个对象的引用。循环里改的是对象的字段，不是新的数据副本。merged 一直持有这些对象，不需要额外"保存"
                 */
                // 给这个块加分 RRF Reciprocal Rank Fusion，倒数排名融合。一种把多个排行榜合并成一个排行榜的方法。
                chunk.addScore(1.0 / (rrfK + i + 1));
                // 把这个块的父块原文记上去
                chunk.setParentContent(h.parentContent());
                // 把章节路径记上去
                chunk.setHeadingPath(h.headingPath());
            }
            for (int i = 0; i < bm25Hits.size(); i++) {
                Bm25Hit h = bm25Hits.get(i);
                RetrievedChunk chunk = merged.computeIfAbsent(new ChunkKey(h.docId(), h.chunkIndex()),
                        k -> new RetrievedChunk(h.docId(), h.chunkIndex(), h.content()));
                chunk.addScore(1.0 / (rrfK + i + 1));
                chunk.setParentContent(h.parentContent());
                chunk.setHeadingPath(h.headingPath());
                // 多查询检索时同一词可能被多个查询重复命中，去重
                /**
                 * 把本次命中的词逐个加进 chunk 的命中词列表，已有的跳过（去重）
                 *
                 * 举例
                 * 第1轮 q=原问题，命中词 [年假, 制度]
                 *    → chunk.matchedTerms = [年假, 制度]
                 *
                 * 第2轮 q=改写1，命中词 [年假, 申请]
                 *    → "年假" 已存在，跳过
                 *    → "申请" 加入
                 *    → chunk.matchedTerms = [年假, 制度, 申请]
                 */
                for (String term : h.matchedTerms()) {
                    if (!chunk.getMatchedTerms().contains(term)) {
                        chunk.getMatchedTerms().add(term);
                    }
                }
            }
        }

        /**
         * 召回 = 从全库里把"可能相关"的找出来
         * 精排 = 对召回来的 20 个候选，用更贵的模型重新打分排序
         */
        // 两条路一条都没召回 → 直接返回空，后面不用跑
        if (merged.isEmpty()) {
            return RetrievalResult.empty();
        }

        // 【混合检索-4】按 RRF 融合得分排序，取候选集（候选数 > 最终数，留给精排）
        /**
         * merged.values() → 拿到所有块
         * .sorted(...reversed()) → 按 RRF 分降序
         * .limit(...) → 取前 20
         * .toList() → 收成不可变 List
         */
        List<RetrievedChunk> candidates = merged.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                .limit(props.getRerank().getTopN())
                .toList();

        // 【混合检索-5】Rerank 精排 + 截断（精排失败自动降级为 RRF 原顺序）
        List<RetrievedChunk> ranked = rerankService.rerank(query, candidates, r.getFinalTopK());

        // 【混合检索-6】父级块展开（small-to-big）：命中的子块替换为其父级块文本，
        // 同一父块的多个子块去重（保留得分最高者），喂给 LLM 的上下文更完整
        List<RetrievedChunk> expanded = expandToParents(ranked);

        // 回填文件名（溯源展示）
        /**
         * 把每个 chunk 关联的文档名查出来，填到 chunk 上，方便前端展示"这条答案来自哪个文件"
         */
        Map<Long, String> fileNameMap = documentMapper.selectBatchIds(
                        expanded.stream().map(RetrievedChunk::getDocId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Document::getId, Document::getFileName, (a, b) -> a));
        expanded.forEach(c -> c.setFileName(fileNameMap.getOrDefault(c.getDocId(), "")));

        return new RetrievalResult(expanded, maxSimilarity);

        /**
         * query
         *   ↓
         * ⓪ Query 改写 → queries = [原问题, 改写1, 改写2]
         *   ↓
         * ① for (String q : queries) {
         *        向量检索 top10  ┐
         *                       ├→ RRF 累加进 merged
         *        BM25 检索 top10 ┘
         *    }
         *   ↓
         * ② merged = 混合检索结果（粗排：按 RRF 分取前 20）
         *   ↓
         * ③ 精排 → 截到 5
         *   ↓
         * ④ 父级块展开
         *   ↓
         * ⑤ 回填文件名
         */
    }

    /** 父块展开 + 按父块去重（输入已按得分降序，先到者得分最高） */
    /**
     * 父级块展开（small-to-big）：把命中的子块替换为其父块内容，
     * 并对同一父块下的多个子块去重（只保留第一个 / 得分最高的那个），让喂给 LLM 的上下文更完整。
     */
    private List<RetrievedChunk> expandToParents(List<RetrievedChunk> ranked) {
        Map<String, RetrievedChunk> byParent = new LinkedHashMap<>();
        for (RetrievedChunk c : ranked) {
            // 如果这个 chunk 有父块内容 → 用父块内容本身作为 key。
            //如果没有父块（null）→ 用 " child:docId:chunkIndex" 拼一个唯一标识作为 key。
            String key = c.getParentContent() != null
                    ? c.getParentContent()
                    : "\0child:" + c.getDocId() + ":" + c.getChunkIndex();
            /**
             * 为什么用父块内容当 key？
             * 因为同一个父块切出的多个子块，它们的 parentContent 是完全相同的字符串。
             * 举例：父块 P 切成子块 c1、c2、c3，那么：
             *
             * c1.getParentContent() = "P 的完整内容"
             * c2.getParentContent() = "P 的完整内容"   ← 相同
             * c3.getParentContent() = "P 的完整内容"   ← 相同
             *
             * 用这个字符串当 key，c1/c2/c3 会落到同一个 key，从而实现"同一父块只保留一个"。
             */
            if (!byParent.containsKey(key)) {
                if (c.getParentContent() != null) {
                    // 内容变成父块全文
                    c.setContent(c.getParentContent());
                }
                /**
                 * 存在 byParent 里的，是每个父块下"第一个被遍历到的"子块——也就是该父块下得分最高的子块。
                 */
                byParent.put(key, c);
            }
        }
        return List.copyOf(byParent.values());
    }

    /** 片段唯一键（同文档同序号） */
    private record ChunkKey(Long docId, Integer chunkIndex) {
    }
}
