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

/**
 * 文档一变更就调 rebuild——它只有一行 indexes.remove(kbId)，把该库的索引从内存 ConcurrentHashMap 里删掉，
 * 只作废不重建；用户下次检索时，search 第一行用 computeIfAbsent(kbId, this::build) 发现索引不存在，
 * 就触发 build 从 PG 用 loadChunksByKb 捞出全库块，逐块交给 JiebaUtil.tokenize 分词、
 * 把 term→块号→词频累加进倒排表 postings，同时记录每块长度 docLen 与平均长度 avgDocLen，
 * 打包成不可变的 KbIndex 快照存进 Map；随后 search 把查询串也按同一口径分词，
 * 对每个 term 从倒排表取出所有含该词的块（不是扫全库），按 BM25 公式 idf × 饱和词频 × 长度归一化
 * 累加得分并顺手记下命中词 matchedTerms，最后用一个容量为 topK 的最小堆筛出分数最高的几块，倒序返回 Bm25Hit。
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
    /**
     * 拿索引 → 分词 → 逐个词给块加分 → 取分数最高的 topK 个块
     */

    /**
     *
     * postings = {
     *     "公司" → {0=1, 1=1},     ← 外层 key="公司"，把"公司"相关的行收在一起
     *     "年假" → {0=1, 2=1},     ← 内层 key=块下标，value=词频
     *     "政策" → {0=1},
     *     "员工" → {1=1},
     *     "手册" → {1=1},
     *     "申请" → {2=1},
     *     "流程" → {2=1}
     * }
     *「年假」出现在 块0 中，出现 1 次
     *「年假」出现在 块2 中，出现 1 次
     *
     * 层级	        存什么
     * 外层 key	    词
     * 内层 key	    块下标
     * 内层 value	词频
     */
    public synchronized List<Bm25Hit> search(Long kbId, String query, int topK) {
        // 拿索引
        // 从内存缓存里取这个知识库的倒排索引；如果还没有，就把这个库的所有块从数据库捞出来现建一份存进去；已经有的话直接返回，一次数据库都不查。
        KbIndex index = indexes.computeIfAbsent(kbId, this::build);
        // 空库直接返回空列表
        if (index.docs().isEmpty()) {
            return List.of();
        }

        // 把查询分词
        /**
         * 建索引时也用同一个 JiebaUtil.tokenize。
         * 两边口径必须一致——如果索引时把"知识库问答"切成"知识/库/问答"，
         * 查询时却当成一个词"知识库问答"，就永远匹配不上，直接漏召。用同一个工具类封装就是为了防这个。
         */
        List<String> terms = JiebaUtil.tokenize(query);
        // 每个块一个分数 数组下标就是块的编号
        // 记录的是第 i 个文档块（chunk）对当前查询的最终 BM25 相关性总分
        double[] scores = new double[index.docs().size()];
        // 每个命中了查询词的文档块，具体命中了哪些查询词
        Map<Integer, List<String>> matchedTerms = new HashMap<>();

        // 对查询的每个词累加 BM25 得分，并记录命中词
        // 外层遍历查询的每个词，内层遍历含这个词的所有块：
        for (String term : terms) {             // 外层：每个查询词
            /**
             * index.postings()	拿到整个倒排索引，类型是 Map<String, Map<Integer, Integer>>
             * .get(term)	    用查询词 term 去查，拿到这个词对应的 posting list
             * Map<Integer, Integer> postings	结果是“块下标 → 词频”的映射
             *
             * 查询出来的效果
             *
             * 假设索引里有三块内容：
             * 块0: 苹果 手机 苹果
             * 块1: 苹果 推荐
             * 块2: 手机 很好用
             *
             * 倒排索引 index.postings() 大致是：
             * "苹果" → { 0 → 2, 1 → 1 }
             * "手机" → { 0 → 1, 2 → 1 }
             * "推荐" → { 1 → 1 }
             *
             * 现在查询词 term = "苹果"：
             * Map<Integer, Integer> postings = index.postings().get("苹果");
             *
             * 拿到的 postings 就是：
             * { 0 → 2, 1 → 1 }
             *
             * 含义：
             * 块 0 包含“苹果”，出现了 2 次
             * 块 1 包含“苹果”，出现了 1 次
             * 块 2 不包含“苹果”，所以不在结果里
             */
            Map<Integer, Integer> postings = index.postings().get(term);
            if (postings == null) {
                continue;   // 库中无此词 → 跳过
            }
            int df = postings.size(); // 有多少块含这个词
            // 计算某个查询词的 IDF（逆文档频率），也就是“这个词有多罕见、有多大的区分度”
            // 核心思想：一个词越罕见，IDF 越大，权重越高；越常见，IDF 越小。
            double idf = Math.log(1 + (index.docs().size() - df + 0.5) / (df + 0.5));
            for (Map.Entry<Integer, Integer> entry : postings.entrySet()) { // 内层：每个含该词的块
                int i = entry.getKey();     // 块下标
                int tf = entry.getValue();  // 词频
                double dl = index.docLen()[i]; // 这个块的字符数
                // 根据“词有多罕见（IDF）、词出现几次（TF）、块有多长（dl）以及平均块长”四个指标，综合算出一个分数
                // BM25 分数越高越好，分数越高代表这个块和查询越相关
                scores[i] += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * dl / index.avgDocLen()));
                // 把当前查询词 term 记录到“第 i 个块命中了哪些词”的列表里
                matchedTerms.computeIfAbsent(i, k -> new ArrayList<>()).add(term);
            }
        }

        // 最小堆取 TopK
        /**
         * PriorityQueue 默认是小顶堆，堆顶是最小元素
         * 比较器按 score 排序，所以堆顶是分数最低的那个 Bm25Hit
         * 注意这里没有加 reversed()，所以是小顶堆，不是大顶堆
         */
        // PriorityQueue<Bm25Hit> 是 Java 集合框架里的优先队列，用来存储 Bm25Hit 对象，并保证每次取出的都是当前队列里优先级最高（或最低）的那个。
        PriorityQueue<Bm25Hit> pq = new PriorityQueue<>(Comparator.comparingDouble(Bm25Hit::score));
        // 遍历所有块
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] <= 0) { // scores[i] <= 0 的块直接跳过：分数为 0 说明没命中任何查询词，不参与候选
                continue;
            }
            ChunkRef ref = index.docs().get(i); // 用下标拿到块的正排信息
            // 把第 i 个块封装成一个 Bm25Hit 对象，然后放进优先队列（小顶堆）
            pq.offer(new Bm25Hit(ref.docId(), ref.chunkIndex(), ref.content(), scores[i],
                    ref.parentContent(), ref.headingPath(), matchedTerms.getOrDefault(i, List.of())));
            // 维护堆大小不超过 topK
            if (pq.size() > topK) {
                pq.poll();
            }
        }
        // 把优先队列（小顶堆）里的结果倒进列表，按分数从高到低排序，然后返回
        List<Bm25Hit> hits = new ArrayList<>(pq);
        hits.sort(Comparator.comparingDouble(Bm25Hit::score).reversed());
        return hits;
    }

    /** 全量构建某知识库的倒排索引 */
    /** 加载所有块 → 逐块分词 → 填充倒排表 → 记录每块长度 → 算平均长度 → 封装成 KbIndex */
    private KbIndex build(Long kbId) {
        // 从数据库 取 kbId这个知识库 下面的所有块
        List<ChunkRef> chunks = vectorStoreDao.loadChunksByKb(kbId);
        Map<String, Map<Integer, Integer>> postings = new HashMap<>(); // 倒排表：词 → 块下标 → 词频
        double[] docLen = new double[chunks.size()]; // 每块多少字符
        long totalLen = 0; // 所有块字符数之和（临时累加器）
        for (int i = 0; i < chunks.size(); i++) { // 逐块处理
            // 分词并塞进倒排表
            for (String term : JiebaUtil.tokenize(chunks.get(i).content())) { // 取第 i 个块的子块文本进行分词 接着逐个取出每个词
                postings.computeIfAbsent(term, t -> new HashMap<>()).merge(i, 1, Integer::sum);
            }
            // 记下每个块有多长，并累加总长——为 BM25 的长度归一化准备数据
            docLen[i] = chunks.get(i).content().length();
            totalLen += docLen[i];
        }
        // 算平均长度 → 打包返回
        double avgDocLen = chunks.isEmpty() ? 1 : (double) totalLen / chunks.size();
        return new KbIndex(chunks, postings, docLen, avgDocLen);
    }

    private record KbIndex(List<ChunkRef> docs,
                           Map<String, Map<Integer, Integer>> postings,
                           double[] docLen,
                           double avgDocLen) {
    }
}
