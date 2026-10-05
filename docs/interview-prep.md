# 面试背记清单（enterprise-RAG）

> 用法：数字表和链路图每天过一遍；问题清单遮住右列口头自测。
> 完整口述答案在 [interview-notes.md](interview-notes.md)（5 道必考题 + 开场 30 秒）；选型对比与踩坑全文在 [README.md](../README.md) 两个「面试素材」章节；工程题对应 [CLAUDE.md](../CLAUDE.md)「关键不变量」。

## 一、两条链路（白板级）

高频开场题：「讲一下你的检索流程。」背到每步都能说出「失败降级成什么」。

### 上传（DocumentService.processDocument）

1. Tika 解析（字节流 + writeLimit 200 万字符防 OOM）
2. 标题感知两级分块（ChunkingService.chunkStructured）：`第X章/第X条` 强制断块；子块 500 字（检索/向量化对象）+ 父块 2000 字（喂 LLM）；块带 heading_path / parent_index / parent_content；标题块跳过 overlap；父块 ≤ 子块时退化为单级
3. 父块摘要（SummaryService）：LLM 一句话摘要，单块失败跳过
4. 批量向量化（EmbeddingService）：子块文本 + 摘要文本同批，20/批 + 重试
5. 双表入库：document_chunk（pgvector）+ chunk_summary
6. BM25 重建（bm25IndexService.rebuild(kbId)）

### 问答（QaService.ask / askStream）

0. 多轮会话：conversationId 非空 → 双归属校验（user + kb，不匹配 404）并取最近 3 轮历史；为空 → 新建会话，id 随 meta 首帧回传
0. 路由（仅 /api/ask）：LLM 从 listMine 白名单选 ≤3 个相关库；单库直通、无候选 400、调用失败降级全部库
1. Query 改写（3 条，失败降级原问题）
2. 摘要树定范围：摘要 Top3 → (doc_id, parent_index) 范围内检索；摘要为空降级全库
3. 双路召回：向量 Top10（`<=>` 余弦）+ BM25 Top10（附命中词）
4. RRF 融合：k=60，跨查询累积（同一块被多条查询命中则分数叠加）
5. Rerank 精排：候选 20 条逐对打分（失败降级 RRF 序）
6. 父块展开：small-to-big，子块换父块文本，同父块去重留高分
7. 兜底判定：maxSimilarity < 0.4 → 固定话术直接返回，不调 LLM
8. Prompt：注入 [来源n]《文件》第x段 + 历史（最终进 Prompt 5 条）→ LLM（temp 0.1）；流式首 token 前断连自动重试一次
9. 审计落库（qa_log，conversation_id 可空，NULL=单轮）

## 二、硬数字表（背熟）

| 类别 | 数字 |
|---|---|
| 分块 | 子块 500 字符 / 重叠 50 / 父块 2000 |
| 召回 | 向量 Top10 + BM25 Top10 |
| 融合精排 | RRF k=60；精排候选 20；进 Prompt 5 条 |
| 辅助 | 改写 3 条；摘要 Top3（≤60 字）；路由 ≤3 库 |
| 模型 | LLM DeepSeek-V3.2（temp 0.1，max 1024 tokens）；Embedding bge-m3 1024 维（20/批）；Rerank bge-reranker-v2-m3 |
| 阈值 | min-similarity 0.4（命中样本相似度均 >0.54，可收紧到 0.5） |
| 评测 | Hit@5 = 100%（4 文档 / 41 块 / 30 标注；平均相似度 0.712） |
| 对比实验 | 纯向量 90%；纯 BM25 100%；无精排 100%；无改写 100% |
| 性能 | 冷启动 ~93s（jieba 词典 + BM25 构建）；稳态 5~7s/问 |
| 压测 | 接口层 3225 QPS / p95 16ms（并发 20）；RAG 链路 QPS 0.8 / p99 27.3s（并发 5，瓶颈=外部模型 API） |
| 其他 | 32 个单测；限流 10/min；上传 20MB / Tika 200 万字符；分类取样 2000 字；精排分 0.89~0.96 |

## 三、决策对自查（遮住右列）

| 决策 | 一句话锚点 | 完整答案 |
|---|---|---|
| 分块 500/50 怎么定 | 精度 vs 上下文权衡；重叠防边界断裂；靠评测拍板 | interview-notes §1 |
| 为什么标题感知 + small-to-big | 结构边界优先落刀；子块定位准、父块上下文全 | §1 |
| pgvector vs Milvus/Chroma | 百万级内「够用 + 零新增组件」；kb_id 过滤是普通列 | §2 |
| 为什么混合检索 | 向量管「意思像」、BM25 管「字面像」 | §3 |
| RRF 为什么不直接加权 | 两类得分量纲不同（0~1 vs 无上界），按排名融合 | §3 |
| RRF 后为什么还要 Rerank | RRF 只是位置融合；交叉编码器逐对精算 | README 选型 §3 |
| 摘要树解决什么 | RAPTOR 简化版；先定父块范围再检索，库大不降精度 | interview-notes §3 |
| 幻觉怎么抑制 | 三层：工程兜底（0.4 短路）+ Prompt 约束 + 检索质量 | §4 |
| RAG vs 微调 | 高频更新 + 强溯源 + 隔离 → RAG 主场 | §5 |
| 为什么手写 BM25 不用 ES | 零中间件；10 万块级够用边界已写明；接口已抽象可替换 | README 选型 §4 |
| 为什么手写 SQL 不用 PgVectorEmbeddingStore | 表结构自控；隔离/溯源列可索引；能讲清底层 | README 选型 §5 |
| 为什么 Tika | 统一入口，加格式零成本 | README 选型 §2 |
| 为什么 OpenAI 兼容协议 | 一个 key 覆盖三模型；换端点零代码 | README 选型 §6 |
| 阈值 0.4 怎么来的 | 命中样本相似度反推；领域相关 | README 坑 §6 |
| 中文 BM25 效果靠什么 | 分词口径决定效果；SEARCH 模式 + 统一 JiebaUtil | README 坑 §4 |

## 四、工程题 = 11 条不变量（CLAUDE.md）

| 场景 | 锚点 |
|---|---|
| 越权怎么防 | requireAccess（404/403）+ pgvector SQL 强制 kb_id 过滤 + 路由白名单 listMine —— 三层 |
| 双库一致性 | 无分布式事务；document 状态机 PARSING/READY/FAILED + 失败补偿（删片段、标 FAILED） |
| BM25 失效 | 文档增删、删库后必须 rebuild(kbId)，否则命中已删数据 |
| small-to-big 数据形态 | content=子块（检索对象）、parent_content=父块（喂 LLM）；parent-size ≤ size 退化为单级 |
| 摘要同生命周期 | 删文档/删库必须同时删 chunk_summary，否则摘要检索命中已删文档 |
| 降级六件套 | 改写→原问题、精排→RRF 序、摘要→全库、分类→手动、路由→全库、兜底→固定话术 |
| SSE 两条错误通道 | 分界线=响应是否已提交；检索/路由/会话解析全部先于首个事件 |
| 异步上下文 | SSE 回调跑在 ForkJoinPool 线程，ThreadLocal 为空 → userId 提前取好传入 |
| ASYNC/ERROR 放行 | SecurityConfig dispatcherTypeMatchers(ASYNC, ERROR).permitAll() |
| 多轮归属 | user + kb 双校验；统一会话 kb_id=NULL 只校验 user |
| 可观测性 | health/info 公开；metrics 仅 ADMIN |

## 五、坑故事（STAR，三个主力）

### 故事 1：双数据源「抢占主库」（最值钱）

- **现象**：启动正常，但 MyBatis-Plus 查 MySQL 报「relation 不存在」—— SQL 实际全打到了 PG
- **定位**：PG 数据源 bean 先于 Boot 自动配置注册 → MySQL 自动配置因 `@ConditionalOnMissingBean` 退避
- **修复**：主数据源手动声明 `@Primary`；连带两个：`spring.datasource.url` 绑不到 HikariDataSource（setter 是 jdbcUrl）→ 经 `DataSourceProperties` 中转；`SqlParameterSourceUtils.createBatch` 把 MapSqlParameterSource 当 JavaBean 反射 → 直接传 `SqlParameterSource[]`
- **启示**：多数据源必须显式控制主从，不能赌自动配置顺序

### 故事 2：SSE 的「死区」（响应提交后异常无法返回 JSON）

- **现象**：流式接口偶发空白响应 / 500 空 body
- **定位**：SseEmitter 发出首帧后响应已 commit，之后的异常再也无法走 GlobalExceptionHandler 写 JSON
- **修复**：requireAccess / 限流 / 会话解析 / 路由 / 检索全部提到**首个事件之前**同步完成；异常响应显式 `contentType(application/json)`（`produces=text/event-stream` 协商不出 JSON 会退化成 500 空响应体）
- **启示**：SSE 有两条错误通道，分界线是「响应是否已提交」

### 故事 3：分词口径决定 BM25 效果

- **现象**：BM25 召回不理想
- **定位**：jieba 默认模式把「知识库」切成一个词，与文档里的切法对不上
- **修复**：统一走 JiebaUtil 的 SEARCH 模式，索引与查询同一口径
- **启示**：中文 BM25 的瓶颈常在分词，不在公式

备用（一句话版）：LangChain4j 1.7 API 断裂（generate→chat、embed 包 Response）；Lombok `is` 前缀 + Jackson 字段名陷阱；Tika 不加 writeLimit 解析大 PDF 会 OOM；embedAll 一次太多触发限流；LLM 输出带行首编号把 id 拼错（取最后一个数字串修复）；pgvector < 0.5 没有 HNSW。

## 六、面试官问题清单

### 6.1 已有完整口述答案的 5 题（interview-notes.md）

分块权衡 / pgvector 选型 / 混合检索 / 幻觉抑制 / RAG vs 微调。

### 6.2 检索细节追问

| 问题 | 锚点 |
|---|---|
| RRF 公式？k 为什么 60？ | score = Σ 1/(k+rank)，按排名融合消除量纲差异；60 是原论文推荐值，k 越大排名差异越平滑 |
| 跨查询怎么累积？ | 同一 chunk 在多条改写查询的结果里出现，RRF 分数累加 |
| Rerank 为什么用交叉编码器？和双塔区别？ | 双塔分开编码可预计算、快但粗（用于召回）；交叉编码器 query+doc 拼接逐对打分、准但贵（只打候选 20 条） |
| 指代追问（「那它呢」）怎么处理？ | 最近 3 轮历史注入 Prompt（规则允许结合上下文理解指代），不是靠改写 |
| 摘要树怎么定范围？摘要为空呢？ | 摘要 Top3 → (doc_id, parent_index) 集合内检索；为空降级全库 |
| 评测为什么用 Hit@5 而不是 BLEU/ROUGE？ | 检索评测看命中（与人工判断一致且便宜）；BLEU/ROUGE 是生成质量指标，生成侧只做了冒烟 |
| 分块参数改过吗？前后数据？ | 500/50 起步；标题感知分块（块更细）后平均相似度 0.654 → 0.712 |

### 6.3 工程类

| 问题 | 锚点 |
|---|---|
| 用户 A 怎么看不到 B 的知识库？ | 三层：requireAccess + SQL kb_id + listMine 白名单 |
| 两个数据库怎么保证一致？ | 无分布式事务；状态机 + 失败补偿 |
| 删文档 / 删库要删哪些？ | document_chunk + chunk_summary + BM25 rebuild |
| 外部模型挂了怎么办？ | 降级六件套（见第四节） |
| SSE 怎么实现的？为什么不用 WebSocket？ | SseEmitter；需求是单向推送；前端不能用 EventSource（POST + Authorization 头）→ fetch + ReadableStream 手写帧解析 |
| 限流怎么做的？ | 用户级滑动窗口 10/min（/ask 与 /ask/stream 共用）；单机内存，分布式要换 Redis |
| 多轮会话怎么设计？ | conversation 表；user+kb 双校验；最近 3 轮注入；qa_log.conversation_id NULL=单轮 |
| 统一问答怎么选库？ | LLM 从 listMine 选 ≤3；单库直通 / 无候选 400 / 失败降级全库；检索 SQL 仍 kb_id IN |
| 大文件 / 扫描件怎么处理？ | Tika writeLimit 200 万字符防 OOM；扫描件无文本层不支持（需 OCR） |
| 自动分类怎么做的？ | Tika 取样 2000 字 → LLM 输出 `id|理由` → 取最后一个数字串（防编号拼接）→ 必须命中候选集（挡幻觉）→ 前端预选、人工确认 |

### 6.4 性能与扩展

| 问题 | 锚点 |
|---|---|
| 瓶颈在哪？ | 外部模型 API 排队：单问 2~3 次 LLM 调用（改写 + 精排），压测 QPS 0.8 是模型排队不是服务瓶颈 |
| 冷启动 93s 哪来的？ | jieba 词典加载 + BM25 全量构建；可预热 |
| 100 万文档会怎样？ | HNSW 参数调优；BM25 内存 O(块数) → 迁 pg_search/ES；增量索引；摘要树已缩小检索范围；加缓存 |
| 并发上不去怎么优化？ | 上传异步化（已留 rag.upload.async）、消息队列削峰、检索缓存、低延迟可关 query-rewrite |

### 6.5 危险问题预案（面试官读了数据必问）

**Q1：「无精排、无改写也 100%，你加它们干嘛？」**
诚实答：30 条标注集偏小且偏关键词型；精排/改写的收益在更大规模、更口语化的查询上（行业共识）；成本受控（精排只打 20 条，改写可配置关闭）。不硬吹。

**Q2：「纯 BM25 也 100%，向量检索的价值在哪？」**
同因；向量管口语 / 同义 / 改写型问法（「怎么涨工资」vs「调薪制度」），对比实验的存在就是为了量化各组件贡献。

**Q3：「你觉得项目哪块不行？」**（主动暴露边界，每条配一句怎么改）

- 测试集只有 30 条且偏关键词型 → 扩标注集 + 加口语化样本
- 摘要串行生成慢（长文档可达数小时）→ 批量摘要 / 并行 + 超时降级
- BM25 全量重建 O(全部块) → 增量更新 + 读写锁去串行
- 限流是单机内存版 → Redis 计数器
- 扫描件不支持（需 OCR）
- 没有生成质量评测（只有检索评测 + 冒烟）→ 加人工 / 模型裁判

## 七、准备方法

1. 白板画两条链路，各限时 3 分钟，能顺手标出每步降级路径
2. 决策对遮住右列自测；每个「为什么不用 Y」准备反方论证（「为什么不用 ES？」「RRF 为什么不调权重？」）
3. 三个坑故事用 STAR 练，重点讲「怎么定位的」（SQL 落到哪个库 / 响应是否已提交 / 对齐分词口径）
4. 6.5 的 Q1~Q3 背到张嘴就来
5. [interview-notes.md](interview-notes.md) 的 5 题口头过 3 遍，录音自查卡壳点
