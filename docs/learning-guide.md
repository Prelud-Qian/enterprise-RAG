# RAG 零基础学习走读笔记

> 面向对象：有 Java/Spring/MySQL 基础，但完全没接触过 RAG 的自己。
> 用法：按天读。每天 = 概念（大白话）→ 代码导读（对照源码看）→ 动手实验 → 面试问答。
> 底线任务：第 4 天结束时，`ChunkingService`、`Bm25IndexService`、`RetrievalService`、`QaService` 四个类要能解释每一行。

---

## 第 0 天：先跑起来，带着问题读代码

**目的**：建立"数据怎么流"的整体印象，之后每天的代码才有落点。

1. 启动应用（README 方式二），打开 Swagger：`http://localhost:9090/swagger-ui.html`
2. 传一份文档（员工手册.docx），记下返回的 docId 和 chunkCount
3. 用 `/search` 问一个问题，观察返回的 sources 字段
4. 查 MySQL 的 `qa_log` 表，看 `retrieved_context` 字段——**这就是注入给大模型的"开卷资料"**，RAG 全链路的目的就是产出这一列

**带着这三个问题读后面每天的代码**：
- 这份"开卷资料"是怎么从文档变成的？（第 2~3 天）
- 检索时怎么挑出最相关的片段？（第 4~6 天）
- 怎么保证模型不乱编？（第 7 天）

---

## 第 1 天：RAG 是什么

### 概念（大白话）

大模型（LLM）像一个**知识渊博但记忆停在训练那天的闭卷考生**：
- 不知道训练之后发生的事（企业新制度、新产品）
- 被问到不会的题会**编**（幻觉）——因为它被训练成"必须回答"
- 答对了你也不知道依据在哪（无法溯源）

**RAG（Retrieval-Augmented Generation，检索增强生成）= 开卷考试**：
把企业文档切碎、向量化存进数据库 → 用户提问时先**检索**出最相关的片段 → 把片段塞进 Prompt → 模型**照着资料回答**并注明出处。

本项目链路（必须默画到能不看 README 画出来）：

```
入库（离线）：上传 → Tika 解析 → 标题感知两级分块 → LLM 父块摘要
            → BGE-M3 向量化 → pgvector 入库 → BM25 索引重建
问答（在线）：权限校验 → 多查询改写 → 摘要树定范围 → 向量+BM25 双路召回
            → RRF 融合 → Rerank 精排 → 父块展开 → 幻觉兜底 → Prompt → LLM → 审计落库
```

### 面试问答

**Q：用一句话解释 RAG？**
> 把企业文档检索后作为上下文喂给大模型，让它"照着资料答题"，解决知识时效、幻觉和溯源三个问题。

**Q：RAG 和微调的区别？**
> 微调把知识写进模型权重（闭卷背下来），RAG 把资料放在外面考试时查（开卷）。企业知识高频更新、要求溯源，所以用 RAG。

---

## 第 2 天：Embedding 与向量检索（地基中的地基）

> 📖 今天读：`service/EmbeddingService.java:29-73`（embed/embedBatch/维度校验/重试）、`dao/pg/VectorStoreDao.java:52-70`（searchByKb 的 `<=>` SQL）。IDE 里 Ctrl+G 输入行号直接跳。

### 概念（大白话）

**Embedding（向量化）= 把一段文字压缩成 1024 个数字**（一个 1024 维向量）。
神奇之处：**语义相近的文字，向量在空间里靠得近**。"员工年假怎么休"和"带薪休假制度"字面完全不同，但向量距离很近。

**相似度**：两个向量夹角的余弦（cosine）。方向越一致值越接近 1，越无关越接近 0。
两个事实记牢：
1. 向量都做了归一化（长度=1）时，余弦相似度 = 两个向量的点积，计算极快
2. 本项目用 BGE-M3 模型，1024 维是它输出的固定维度（存表、建索引都和这个维度强绑定）

### 代码导读
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

**`EmbeddingService`**（`service/EmbeddingService.java`）——模型 API 的封装：
- `embed(String)`：单条向量化（问答时给用户问题用）
- `embedBatch(List<String>)`：批量向量化（入库时用），一次请求传 20 条，比单条快 20 倍
- `withRetry`：模型 API 偶尔超时/限流，自动重试 2 次（指数退避 1s/2s）——**所有外部依赖都要有重试或降级，这是本项目贯穿始终的设计**
- `checkDimension`：校验返回确实是 1024 维——防止配错模型（比如配了个 512 维的）导致入库和查询的空间不一致

**`VectorStoreDao.searchByKb`**（`dao/pg/VectorStoreDao.java`）——向量检索的 SQL，全项目最该背的一行：

```sql
SELECT ..., 1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
FROM document_chunk c
WHERE c.kb_id = :kbId          -- 知识库隔离：只查当前用户的库
ORDER BY c.embedding <=> CAST(:embedding AS vector)
LIMIT :topK
```

- `<=>` 是 **pgvector 扩展提供的余弦距离运算符**：距离越小越相似
- `1 - 距离` = 余弦相似度（0~1，越大越相似）
- `ORDER BY 距离 LIMIT 10` = 取最相似的 10 块（Top10）
- `WHERE kb_id` 是数据隔离在存储层的兜底（权限章节会再讲）

### 动手实验

在 PG 里跑：
```sql
-- 自己和自己比，相似度必须是 1
SELECT 1 - (embedding <=> embedding) FROM document_chunk LIMIT 1;
-- 看两个不同块的相似度（通常 < 0.5）
SELECT 1 - (a.embedding <=> b.embedding)
FROM document_chunk a, document_chunk b
WHERE a.id < b.id LIMIT 1;
```

### 面试问答

**Q：为什么用余弦相似度？**
> 语义由向量方向表达，与长度无关；归一化后余弦=点积，计算快，pgvector 直接有 `<=>` 算子。

**Q：向量维度为什么是 1024？**
> 由 BGE-M3 模型决定，维度越高表达能力越强但存储/计算越贵；换模型必须同步改表结构和索引，所以配置里做了维度校验兜底。

---

## 第 3 天：分块（Chunking）

> 📖 今天读：`service/ChunkingService.java` 的 `43-75`（入口方法）、**`103-142`（buildBase 主循环，重点）**、`144-168`（标题判断/路径栈）、`181-227`（overlap/硬切/路径去重）。

### 概念（大白话）

一篇 2 万字的文档没法整体检索——把"和问题相关的那一小段"从一大坨里找出来，是向量检索的核心矛盾。所以先**切块**：

- **块太大**（1000+ 字）：一块里塞了十个主题，检索"命中"了但相关句子只占 1/10，剩下的全是噪声，还浪费模型上下文
- **块太小**（几十字）：一句话被拦腰截断（"年假 5 天"和"入职满一年"分在两块），检索到哪块都答不全

本项目三层机制，每层解决一个问题：

| 机制 | 解决什么 | 配置 |
|---|---|---|
| 标题强制断块 | 结构完整：第X章/第X条天然是语义边界 | 正则识别标题行 |
| overlap 重叠 50 字 | 边界语义：上一块结尾拼到下一块开头，句子不会被"切漏" | `rag.chunk.size/overlap` |
| 两级分块 small-to-big | 精度与完整兼得：500 字子块检索定位、2000 字父块喂模型 | `rag.chunk.parent-size` |

实测数据（面试可直接引用）：分块从 8 块变 41 块后，检索平均相似度 0.654 → 0.712。

### 代码导读（`ChunkingService`，全项目最好读的类）
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

按方法逐个看：

1. `splitSentences(text)`：用正则 `(?<=[。！？；\n])` 把文本切成句子列表（保留句末标点）
2. `buildBase(sentences, chunkSize, treatFirstAsTitle)`：核心循环，逐句处理三种情况
   - **是标题** → 当前块收口，标题开新块（标题永远是新块的起点）
   - **单句超长** → 硬切成多个 chunkSize 大小的块
   - **正常句子** → 贪心往当前块里追加，超 chunkSize 就收口开新块
3. `isHeadingLine / headingLevel / updatePath`：标题识别与**章节路径**维护
   - 路径是个三级栈：标题(0) / 章(1) / 条(2)，同级覆盖、下级清空
   - 产物就是溯源里的 `headingPath`："员工手册 > 第三章 考勤与休假 > 第五条"
4. `applyOverlap`：下一块开头拼上一块结尾 50 字；**标题开头的块跳过 overlap**（防止上一块尾巴污染标题）
5. `chunkStructured`：两级编排——先按 parentSize 切父块，再在每个父块里切子块；子块路径与父块路径合并去重

### 动手实验

同一份文档分别用 chunkSize 300 / 500 / 800 上传三次，对比 chunkCount 和检索相似度——这就是"参数怎么定"这道面试题的实验依据。

### 面试问答

**Q：chunk 大小怎么定？**
> 先按经验取 500，再用标注集评测对比不同取值，取命中率最高者；结构边界（标题）优先于字符数硬切。

**Q：overlap 会不会让内容重复？**
> 会，重复 50 字换来边界语义不断裂，代价是存储和向量化成本略增，这是有意为之的取舍。

---

## 第 4 天：BM25 与倒排索引（本项目的面试分水岭）

> 📖 今天读：`service/Bm25IndexService.java` 的 `48-92`（search 打分）、`94-108`（build 建索引）、`110-114`（KbIndex 结构）；`util/JiebaUtil.java` 全文（分词口径统一）。

### 概念（大白话）

**BM25 = 关键词检索的经典算法**：看"查询词在文档里出现了多少次、出现在什么样的文档里"来打分。核心是**倒排索引**——像词典：

```
"年假" → [块12, 块30, 块77]      ← 这个词出现在哪些块
"绩效" → [块45, 块46]
```

查询"年假政策"时，只去"年假""政策"两个词对应的列表里找，不用扫全库。

**BM25 公式不用背，记三个直觉**：
1. **词频饱和（k1=1.5）**：一个词出现 5 次和出现 50 次的差别远小于 1 次和 5 次的差别——出现得够多就该"封顶"，否则满篇"年假"的文档永远排第一
2. **文档长度归一化（b=0.75）**：长文档天然出现词更多，不能让它因此占便宜——用"相对长度"而不是"出现次数"打分
3. **IDF（逆文档频率）**：几乎所有块都有的词（"公司""员工"）区分度低，权重低；稀有的词（"十三薪"）权重高

### 代码导读（`Bm25IndexService`）
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

- `KbIndex`：一个知识库一份索引快照 = `docs`（块列表）+ `postings`（词 → 块id→词频）+ `docLen`（每块长度）+ `avgDocLen`（平均长度）
- `build(kbId)`：从 PG 加载全部块 → jieba 分词 → 构建 postings。**索引懒加载**：第一次搜索时才构建，文档增删后 `rebuild()` 整份重建（简单可靠，>10 万块才需要演进为增量索引）
- `search()`：
  - 查询分词（和建索引用同一个 `JiebaUtil`——**分词口径必须一致**，否则索引白建）
  - 对每个词：查 postings → 算 idf → 对命中的块累加 `idf × (tf*(k1+1)) / (tf + k1*(1-b+b*dl/avgdl))`
  - 最小堆取 TopK（只留得分最高的 K 个，不用全排序）
  - 顺带记录每块命中了哪些词 → `matchedTerms`（前端高亮、解释"为什么召回这块"）

### 动手实验

对同一个问题看 `/search` 返回的 `matchedTerms` 字段——它就是"这个块是因为哪些关键词被召回的"的证据。

### 面试问答

**Q：为什么手写 BM25 不用 Elasticsearch？**
> 十万块以内内存倒排足够，零额外中间件；手写能讲清倒排结构和公式含义（这正是面试要展示的）。超过十万块再迁 ES/pg_search，`Bm25IndexService` 接口已抽象，替换成本低。

**Q：k1 和 b 是干嘛的？**
> k1 控制词频饱和强度，b 控制文档长度归一化强度——防止"词多"和"文长"的文档刷分。

---

## 第 5 天：混合检索与 RRF

> 📖 今天读：`service/RetrievalService.java` 的 **`44-132`（retrieve 主流程，重点）**、`134-149`（父块展开）；`service/QueryRewriteService.java:42-71`（改写）。

### 概念（大白话）

两种检索各有盲区，必须合体：

| | 擅长 | 盲区 |
|---|---|---|
| 向量检索 | 语义相近（"怎么涨工资"≈"调薪制度"） | 精确词（工号、人名、专有名词）、口语改写 |
| BM25 | 精确关键词 | 换个说法就查不到 |

**融合方式为什么是 RRF（Reciprocal Rank Fusion）而不是加权？**
两路得分量纲完全不同：余弦相似度是 0~1 的小数，BM25 分数可以是 3.8 也可以是 38——直接相加，谁大谁说了算，没法调。
RRF 只关心**排名**：

```
score(块) = Σ 1 / (k + 排名)
```

k=60 是平滑常数。向量路第 1 名得 1/61≈0.0164，BM25 路第 3 名得 1/63≈0.0159——两路排名都有话语权，且分数永远在 (0, 0.03) 区间，稳定可比。

实测（面试数据）：纯向量 Hit@5=90%，混合检索 100%——**3 道题只有关键词路能命中，混合的价值是实测出来的**。

### 代码导读（`RetrievalService.retrieve`，逐段走读）
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

1. `queryRewriteService.rewrite(query)`：让 LLM 把口语问题改写成多个检索查询（"怎么涨工资"→"调薪制度"），**每个查询独立召回、RRF 跨查询累积**。失败降级为原问题
2. 每个查询循环内：
   - `summaryDao.searchByKb`：先搜父块摘要，命中则确定"相关父块范围"（摘要树，第 6 天细讲）
   - `vectorStoreDao.searchByKb`：向量 Top10（有摘要范围时只在范围内检索）
   - `bm25IndexService.search`：BM25 Top10
3. `merged`（LinkedHashMap，key=docId+chunkIndex）：两路命中写入同一个 `RetrievedChunk`，`addScore(1/(k+rank+1))` 累加——**同一个块被多路/多查询命中，得分自动更高**
4. 按 RRF 得分排序取 Top20 候选 → `rerankService.rerank` 精排取 Top5（第 6 天）
5. `expandToParents`：命中子块替换为父块全文，同一父块的多个子块去重（small-to-big 的"下半场"）
6. 回填文件名 → 组装 `RetrievalResult`（含 `maxVectorSimilarity`，兜底判定用）

### 面试问答

**Q：RRF 里的 k 是什么？**
> 平滑常数，防止排名第 1 和第 2 的分数差太悬殊；k=60 是业界经验值，也可以离线调参。

---

## 第 6 天：精排（Rerank）与摘要树

> 📖 今天读：`service/RerankService.java:48-110`、`service/SummaryService.java:32-50`、`dao/pg/SummaryDao.java:39-57`、`dao/pg/VectorStoreDao.java:72-95`（范围内检索 SQL）。

### 概念（大白话）

**两阶段检索 = 海选 + 决赛**：

- **召回（海选）**：双塔模型。问题和文档各自编码成向量再比——快，能处理海量候选，但精度一般（编码时互不知道对方）
- **精排（决赛）**：交叉编码器。把"问题+片段"拼成一句话整体编码——准，但慢（每个候选都要过一次大模型）

所以只对 RRF 候选 Top20 精排，成本可控。本项目精排模型 `BAAI/bge-reranker-v2-m3`，失败自动降级回 RRF 顺序（**精排是"加分项"，不是"命根子"**）。

**摘要树（RAPTOR 简化版）**：知识库变大后全库检索精度下降，解法是"先粗后细"：
1. 入库时为每个父块生成一句话摘要（LLM），摘要向量化存 `chunk_summary` 表
2. 检索时先拿问题搜摘要（摘要短、语义浓缩，易命中）→ 命中的摘要标出"相关父块范围"
3. 子块检索只在该范围内进行——相当于先翻目录再精读

### 代码导读
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

- `RerankService.callRerankApi`：POST /rerank，注意 `api-format` 配置——硅基流动（顶层 query/documents）和百炼（嵌套 input）协议不同，本项目做了双协议兼容。换模型服务商只需改 yml
- `SummaryService.summarize`：入库时给每个父块生成一句话摘要，单块失败置 null（跳过，不影响主流程）
- `SummaryDao`：摘要表的读写，检索侧 `searchByKb` 返回 (docId, parentIndex) 范围
- `VectorStoreDao.searchByKb(kbId, vec, topK, scopes)`：范围内检索的重载——SQL 用 `(doc_id, parent_index) IN ((1,2),(3,4))` 行构造器过滤

### 面试问答

**Q：精排为什么不直接全量跑？**
> 交叉编码器对每个候选要过一次模型，Top100 全精排成本是 Top20 的 5 倍；召回已经把 100 缩到 20，精排在这 20 里排精度，性价比最高。

**Q：摘要树和直接全库检索差在哪？**
> 摘要短、语义浓缩，检索摘要更容易命中相关主题；命中后把搜索范围缩到几个父块，子块检索的噪声大幅下降。摘要生成失败自动降级全库检索，可用性不受影响。

---

## 第 7 天：幻觉兜底与 Prompt

> 📖 今天读：`service/QaService.java` 的 **`55-123`（ask 主流程，重点）**、`125-137`（会话历史）、`229-239`（上下文拼接）。

### 概念（大白话）

**幻觉的根源**：模型被训练成"必须给答案"，没有依据时它不会说"我不知道"，而是编。

本项目**三层防线**：

1. **工程兜底（最硬）**：检索质量阈值判断——`maxSimilarity < 0.4` 或召回为空时，**根本不调 LLM**，直接返回固定话术"没有找到相关资料"。从源头掐断编造
2. **Prompt 约束（第二层）**：系统提示词强制"只依据参考资料回答，没有就明说没有" + temperature 0.1（低随机性）
3. **二次标记**：模型万一还是输出了"找不到"类话术，审计日志里 `is_fallback` 照样标 1

**证明兜底没过 LLM 的证据**（面试可用）：兜底答案与固定话术**一字不差**——如果经过 LLM，措辞不可能逐字相同。

### 代码导读（`QaService.ask` 完整走读——第 0 天之后的第二个重点类）
> 💻 真代码逐行版：跳到文末「附录：四个核心类逐行走读」对应小节。

1. `requireAccess(kbId)`：权限（第 8 天）
2. `rateLimitService.checkAsk`：限流（防 key 被刷烧钱）
3. 多轮会话：`conversationId` 非空 → 校验归属（user+kb 都匹配，否则 404）→ 取最近 3 轮历史；为空 → 新建会话并返回 id
4. `retrievalService.retrieve`：第 5 天那条链
5. 兜底判定：`retrieval.isEmpty() || maxVectorSimilarity < 0.4` → 固定话术，**跳过大模型**
6. `buildContext`：把 Top5 片段拼成 `[来源1]《文件名》第x段：...`，替换 Prompt 模板的 `{context}`、`{history}`、`{question}` 三个占位符
7. `chatModel.chat`：LLM 调用
8. `qaLogService.save`：审计落库（问题/答案/上下文/来源/耗时/会话）

### 动手实验

问一个库外问题（"比特币值得买吗"），看响应 `isFallback: true` 且答案和固定话术一字不差；再去 qa_log 看这条记录。

### 面试问答

**Q：怎么保证模型不编造？**
> 三层：检索质量不够直接短路不调 LLM（实测兜底答案与固定话术逐字一致，证明没过模型）；Prompt 强约束只依据资料；低温度。另外每次回答都带溯源片段，可人工核验。

---

## 第 8 天：工程细节（RAG 之外，Java 后端面试必问）

> 📖 今天读：`service/DocumentService.java:131-224`（入库主流程+失败补偿）、`service/KnowledgeBaseService.java:75-85`（requireAccess 隔离）、`service/RateLimitService.java:34-49`（限流窗口）。

### 双数据源为什么没有事务（本项目最精彩的工程决策）

MySQL 存业务数据（用户/知识库/文档/日志），PG 存向量。**两个独立的数据库不可能放进一个事务**。一致性靠：

- **状态机**：document 表 status 字段 PARSING → READY / FAILED
- **失败补偿**：入库中途失败，catch 里删掉已入库的片段和摘要、标记 FAILED——保证不产生"有片段无文档"的脏数据

面试讲法：这是"分布式一致性的最终一致方案"，生产环境可升级 Seata/本地消息表。

### RBAC 检索前过滤

"不能检索完再过滤"——本项目两道防线：
1. 服务层 `requireAccess`：非 owner 直接 403，**根本没进检索阶段**（bob 对 admin 的库 5 个接口全部 403 实测）
2. 存储层：所有 PG 检索 SQL 强制 `WHERE kb_id = ?`——即使服务层漏了，存储层也查不到别人的数据

### 降级设计清单（背下来，面试问"怎么保证可用性"直接背）

| 依赖 | 故障时行为 |
|---|---|
| Query 改写 | 降级为原始问题 |
| 摘要树 | 降级为全库检索 |
| Rerank 精排 | 降级为 RRF 顺序 |
| Embedding | 重试 2 次后报错 |
| 检索质量不足 | 固定话术，不调 LLM |

**原则：任何外部依赖故障不能让主链路 500。**

### 压测数据解读

- 接口层 3225 QPS / p95 16ms：Tomcat + JWT + 双数据源毫无压力
- RAG 链路并发 5 时 QPS 0.8 / p99 27s：**瓶颈在外部模型 API 排队**（每请求 2~3 次 LLM 调用），不在自己代码
- 优化方向：关改写省一次调用、换小模型、缓存高频问题、消息队列削峰

---

## 第 9~10 天：口述训练清单

1. **30 秒开场稿**（interview-notes.md 顶部）脱稿
2. **五道必考题**用自己的话复述（不看稿，录音回听）：
   - 分块大小权衡 / pgvector 选型 / 混合检索痛点 / 幻觉抑制 / RAG vs 微调
3. **追问预演**（自己问自己）：
   - "k1、b 是干嘛的？" → 第 4 天
   - "RRF 为什么不用加权？" → 第 5 天
   - "双库一致性怎么保证？" → 第 8 天
   - "瓶颈在哪？" → 第 8 天压测数据
   - "这个参数为什么取这个值？" → 全部指向评测数据（Hit@5 对比实验）

---

## 附录：自己动手跑起来（执行手册）

**前置条件**：enterprise-RAG VM 已开机（存储都在它上面）；本机有 JDK17 的 Maven（已配好）。

**1. 清理旧实例**（本机 9090~9095 可能挂着历史实验实例）：

```bash
netstat -ano | findstr ":909"     # 列出 PID
taskkill /F /PID <每个PID>
```

**2. 启动应用**（数据库地址/端口已写死在 application.yml，只需 key 在环境变量里，一次性配置：`setx DASHSCOPE_API_KEY sk-xxx`）：

```bash
cd D:\JavaProject\enterprise-RAG
mvn spring-boot:run
```

看到 `Started EnterpriseRagApplication` 即成功。

**3. 接口试效果**（`http://localhost:9090/swagger-ui.html`，右上角 Authorize 填 token）：

| 顺序 | 接口 | 看什么 |
|---|---|---|
| 1 | POST /api/auth/login（admin/admin123） | 拿 token |
| 2 | POST /api/kb | 建知识库，记下 kbId |
| 3 | POST /api/documents/upload（file+kbId） | 传自己的 PDF/Word，返回 chunkCount |
| 4 | POST /api/kb/{id}/search | sources 的 headingPath / matchedTerms / score |
| 5 | POST /api/kb/{id}/ask | 答案 + 溯源；问库外问题看兜底 |
| 6 | GET /api/kb/{id}/qa-logs | 审计记录 |

**4. 直查数据库看底层数据**（连 192.168.88.130）：

```
MySQL  root/123456 → enterprise_rag
  qa_log.retrieved_context   ← 注入给大模型的"开卷资料"，RAG 的最终产物
  conversation               ← 多轮会话
PostgreSQL  postgres/123456 → enterprise_rag
  document_chunk             ← 每行一个分块：content/heading_path/parent_content/embedding
  chunk_summary              ← 每行一个父块摘要 + 向量
```

**5. 跑评测**（项目自带的量化验证）：

```bash
cd D:\JavaProject\enterprise-RAG\docs\eval
python eval.py --token <登录拿的JWT> --kb <你的知识库id> --k 5
# 输出：每条问题 HIT/MISS + Hit@5 命中率 + 平均相似度
```

**常见坑**：
- 端口被占 → 换 `SERVER_PORT`
- VM 没开机 → 启动报数据库连接失败，去 VMware 开机
- 上传报"余额不足" → 硅基流动账户充值
- 首问要等 90 秒 → jieba 词典和 BM25 索引冷启动，正常现象

---

## 附录：20 个核心面试题速查

| # | 问题 | 答案关键词 |
|---|---|---|
| 1 | RAG 是什么 | 检索增强生成=开卷考试；时效/幻觉/溯源 |
| 2 | Embedding 是什么 | 文字→1024 维向量；语义近=距离近 |
| 3 | 为什么余弦相似度 | 方向表达语义；归一化后=点积；`<=>` 算子 |
| 4 | chunk 怎么定大小 | 精度 vs 完整权衡；评测数据拍板 |
| 5 | overlap 干嘛的 | 防边界语义断裂，50 字重复是故意取舍 |
| 6 | 倒排索引是什么 | 词→块列表，查词不扫全库 |
| 7 | BM25 k1/b | 词频饱和/长度归一化，防刷分 |
| 8 | 为什么不用 ES | 规模阈值；手写可讲原理；接口已抽象可迁移 |
| 9 | 混合检索解决什么 | 向量管语义、BM25 管字面，互补 |
| 10 | RRF 为什么不用加权 | 量纲不同不可比；按排名融合稳定 |
| 11 | 召回 vs 精排 | 双塔快而粗、交叉编码器慢而准；两阶段 |
| 12 | 精排为什么只跑 Top20 | 成本；召回已缩范围 |
| 13 | 摘要树干嘛的 | 先粗后细：摘要定范围再精检 |
| 14 | 幻觉怎么抑制 | 三层：阈值短路、Prompt 约束、低温度 |
| 15 | 怎么证明兜底没过 LLM | 答案与话术逐字一致 |
| 16 | 双库一致性 | 状态机+失败补偿；升级 Seata |
| 17 | 权限怎么隔离 | 服务层 requireAccess 前置 + SQL 层 kb_id 兜底 |
| 18 | 瓶颈在哪 | 外部模型 API 排队（压测数据） |
| 19 | 参数怎么定的 | 30 条标注集评测，单变量对比实验 |
| 20 | 为什么 Java 不用 Python | 面试岗位栈；手写全链路是稀缺差异点 |

---

## 附录：四个核心类逐行走读（真代码版）

> 使用方法：IDE 里打开对应文件（`Ctrl+点击` 类名可跳转），对照下面的代码块一行行看。
> 目标：读完能解释每一行的"为什么"。四个类读完 = 面试底线达成。

### A. ChunkingService（`service/ChunkingService.java`）——分块

**主循环 `buildBase()`**（约第 103 行），这是全项目最值得逐行读的方法：

```java
for (String s : sentences) {
    boolean headingLine = isHeadingLine(s);
    if (first && treatFirstAsTitle && !headingLine && s.length() <= chunkSize) {
        // 文档首句视作标题（level 0），写入路径根；超长首句按正文处理
        flushBase(current, base, comps, currentStartsHeading);
        current = new StringBuilder(s);
        currentStartsHeading = true;
        comps[0] = s;              // 路径栈第 0 层 = 文档标题
        comps[1] = null; comps[2] = null;
    } else if (headingLine) {
        flushBase(current, base, comps, currentStartsHeading);   // 标题前的内容先收口成块
        current = new StringBuilder(s);                           // 标题自己开新块
        currentStartsHeading = true;
        updatePath(comps, s);                                     // 更新章节路径栈
    } else if (s.length() > chunkSize) {
        flushBase(current, base, comps, currentStartsHeading);
        for (String part : hardSplit(s, chunkSize)) {             // 超长句硬切兜底
            base.add(new BaseChunk(part, currentPath(comps), false));
        }
    } else if (current.length() + s.length() > chunkSize) {
        flushBase(current, base, comps, currentStartsHeading);   // 装不下了 → 收口
        current = new StringBuilder(s);                           // 新句子开新块
    } else {
        current.append(s);                                        // 贪心追加
    }
    first = false;
}
```

**逐行讲**：
- `isHeadingLine(s)`：正则判断"第X章/第X条/一、/1."开头且整行 ≤42 字（太长就不是标题）
- 每个分支第一个动作几乎都是 `flushBase`——"把手里攒的句子收口成一个块"。块的一生：攒 → 收口 → 入 list
- `comps` 是三层路径栈（标题/章/条）：`updatePath` 同级覆盖、下级清空——所以块 5 的路径是"第三章 考勤与休假"，块 6 进了第五条就变成"第三章 > 第五条"
- `currentStartsHeading` 标记本块是否以标题开头——后面 `applyOverlap` 用它决定**跳过 overlap**（防止上一块尾巴污染标题）

**自测**：不看代码，画出"第一条 员工入职满一年后，每年享有五天带薪年假。"这句经过 buildBase 时的完整分支路径。

### B. Bm25IndexService（`service/Bm25IndexService.java`）——倒排索引与 BM25

**索引构建 `build()`**（后半段）：

```java
for (int i = 0; i < chunks.size(); i++) {
    for (String term : JiebaUtil.tokenize(chunks.get(i).content())) {   // 每块先分词
        postings.computeIfAbsent(term, t -> new HashMap<>())            // term → (块号 → 词频)
                .merge(i, 1, Integer::sum);
    }
    docLen[i] = chunks.get(i).content().length();                       // 每块长度
    totalLen += docLen[i];
}
double avgDocLen = chunks.isEmpty() ? 1 : (double) totalLen / chunks.size();
```

**逐行讲**：
- `postings` 就是倒排索引：`"年假" → {块12: 2次, 块30: 1次}`。查询时直接从"年假"这个词的入口取候选块，**不用扫全库**
- `docLen/avgDocLen` 是为 BM25 的长度归一化准备的——长块天然词多，不能让它占便宜

**打分循环 `search()`**（核心三行）：

```java
int df = postings.size();                                  // 文档频率：多少块含这个词
double idf = Math.log(1 + (N - df + 0.5) / (df + 0.5));    // 稀有词权重高，烂大街的词权重低
scores[i] += idf * (tf * (K1 + 1)) /
             (tf + K1 * (1 - B + B * dl / avgDocLen));     // BM25 打分公式
```

**逐行讲**：
- `df` 大（"公司"每个块都有）→ 括号里 (N-df) 小 → idf 接近 0 → 这个词几乎不加分
- 第三行分子 `tf*(K1+1)` 和分母里的 `tf` 相互抵消一部分——**tf 越大分数增长越慢**（词频饱和，k1=1.5 控制饱和速度）
- `B * dl / avgDocLen`：块比平均长时分母变大、分数变小——**长度归一化**（b=0.75 控制惩罚力度）

**自测**：口述"查询『年假政策』时，BM25 从收到分词到输出 TopK 的完整步骤"。

### C. RetrievalService（`service/RetrievalService.java`）——混合检索编排

**摘要树 + RRF 融合段**（retrieve 方法中部）：

```java
// 摘要树检索：先搜父块摘要定范围，子块向量检索只在该范围内执行
if (props.getSummary().getEnabled()) {
    List<SummaryHit> summaryHits = summaryDao.searchByKb(kbId, queryVector, topN);
    if (!summaryHits.isEmpty()) {
        List<Scope> scopes = summaryHits.stream()
                .map(h -> new Scope(h.docId(), h.parentIndex())).distinct().toList();
        vectorHits = vectorStoreDao.searchByKb(kbId, queryVector, r.getVectorTopK(), scopes);
    } else {
        vectorHits = vectorStoreDao.searchByKb(kbId, queryVector, r.getVectorTopK());  // 降级全库
    }
}

// RRF：两类得分量纲不同不可直接相加，按排名融合
for (int i = 0; i < vectorHits.size(); i++) {
    RetrievedChunk chunk = merged.computeIfAbsent(new ChunkKey(docId, chunkIndex), ...);
    chunk.addScore(1.0 / (rrfK + i + 1));       // 向量路第 i 名 → 1/(60+i+1)
}
for (int i = 0; i < bm25Hits.size(); i++) {
    ... addScore(1.0 / (rrfK + i + 1));         // BM25 路同样按排名加分，可跨查询累积
}
```

**逐行讲**：
- `computeIfAbsent`：同一个块可能被向量路和 BM25 路都命中——两路的分要**加到同一个对象上**，所以用 (docId, chunkIndex) 当 key 去重合并
- RRF 的分数只跟排名有关：第 1 名 1/61≈0.0164，第 3 名 1/63≈0.0159——**量纲统一，两路平等对话**
- 多查询改写场景：每个改写查询都跑一遍这个循环，同一块的分数**跨查询累积**——这就是"多查询 RRF"

**自测**：说明"为什么不能用 0.8×向量分 + 0.2×BM25分"这种加权（答案在第 5 天概念节）。

### D. QaService（`service/QaService.java`）——问答编排与兜底

**兜底判定 + Prompt 组装段**（ask 方法中部）：

```java
if (retrieval.isEmpty()
        || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
    // 检索质量不达标 → 固定话术，根本不调 LLM（从源头掐断编造）
    fallback = true;
    answer = FALLBACK_ANSWER;    // "没有找到相关资料，请换个问法或先上传相关文档。"
    sources = List.of();
} else {
    context = buildContext(retrieval.chunks());    // [来源1]《文件名》第x段：...
    String systemPrompt = props.getPromptTemplate()
            .replace("{context}", context)
            .replace("{history}", history.isBlank() ? "（无）" : history)
            .replace("{question}", question);
    ChatResponse response = chatModel.chat(ChatRequest.builder()
            .messages(SystemMessage.from(systemPrompt), UserMessage.from(question)).build());
    answer = response.aiMessage().text();
}
qaLogService.save(kbId, convId, question, answer, sources, context, fallback, latency);
```

**逐行讲**：
- `maxVectorSimilarity < 0.4`：阈值来自评测数据（命中样本相似度都 >0.54）。这个 if 是**防幻觉的核心**——不过关的请求连 LLM 的门都进不去
- `replace("{context}", ...)`：模板占位符替换，`{context}` 装检索片段、`{history}` 装多轮历史、`{question}` 装用户问题——替换完的 systemPrompt 就是发给模型的完整指令
- `messages(SystemMessage, UserMessage)`：SystemMessage = 规则（模型当背景服从），UserMessage = 问题（模型针对回答）
- 最后一行：无论兜底还是正常，**都落 qa_log**——审计不挑路径

**自测**：解释"兜底答案为什么能和固定话术一字不差"（答案：因为压根没经过 LLM，字符串直接返回的）。
