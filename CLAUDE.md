# CLAUDE.md — enterprise-RAG

面试向企业知识库 RAG 问答系统。Spring Boot 3.3 + LangChain4j 1.7 + MySQL(业务) + PostgreSQL/pgvector(向量) + 通义千问/BGE-M3(OpenAI 兼容协议)。纯后端 RESTful，无前端。

## 常用命令

```bash
mvn -q compile          # 编译（本机 Maven 已绑定 JDK17；PATH 默认 java 是 1.8，别用 java 命令验证）
mvn test                # 21 个单元测试（标题分块/BM25/命中词/RRF 融合/多查询/摘要范围检索/父块展开/分词/限流），改检索逻辑必跑
mvn spring-boot:run     # 启动，先决条件见下
python docs/eval/eval.py --token <JWT> --kb 1 --k 5   # 检索评测（Hit@5 报告）
```

启动前：执行 `sql/mysql_schema.sql` + `sql/pgvector_schema.sql`（PG 需装 vector 扩展），设 `DASHSCOPE_API_KEY`。数据库不在本机：README「方式二」有 VM 内 Docker 部署命令。

## 架构（包职责一句话）

```
common/    Result 统一返回、BusinessException、LoginUser、GlobalExceptionHandler
config/    Security(JWT+RBAC)、双数据源(PG 手动装配)、模型 Bean、rag.* 属性类、Swagger
controller/ Auth / KnowledgeBase / Document / Qa / Admin —— 只做参数校验与编排
service/   RAG 全链路：分块、向量化、BM25、Query改写、混合检索、精排、问答、日志
dao/mapper/ MyBatis-Plus（MySQL）   dao/pg/ VectorStoreDao 手写 SQL（pgvector）
entity/    实体 + dto/ + vo/
util/      SecurityUtil、JiebaUtil
```

## RAG 链路（每一步的代码位置）

```
上传: DocumentService.processDocument
  ① Tika 解析(字节流,writeLimit防OOM) → ② ChunkingService.chunkStructured 标题感知两级分块:
     第X章/条强制断块,块带 heading_path(章节路径)/parent_index(父块序号)/parent_content;
     标题块跳过 overlap;parent-size<=size 退化为单级(parent=null,parent_index=0)
  → ②.5 SummaryService LLM 父块一句话摘要(失败单块跳过,检索侧降级) → ③ EmbeddingService 统一批量
     (子块文本+摘要文本一批,20/批+重试) → ④ 双表入库: VectorStoreDao(document_chunk) + SummaryDao(chunk_summary)
  → ⑤ bm25IndexService.rebuild(kbId)
问答: QaService.ask / askStream(SSE)
  ⓪ 多轮会话(可选): conversationId 非空时校验归属(user+kb,否则404)并取最近3轮历史注入 Prompt;
     为空则新建 conversation 行并把 id 返回给客户端
  ① QueryRewriteService 多查询改写(失败降级原问题) → ② 摘要树检索: SummaryDao 搜摘要 Top3 定
     (doc_id,parent_index) 范围 → 范围内 VectorStoreDao 向量 Top10(摘要为空降级全库) + BM25 Top10(含命中词)
  → ③ RRF(k=60) 跨查询累积 → ④ RerankService gte-rerank 精排(失败降级 RRF 序)
  → ⑤ 父级块展开(small-to-big: 子块换父块文本,同父块去重留高分;保留 headingPath/matchedTerms)
  → ⑥ 兜底判定(maxSimilarity<0.4 不调 LLM) → ⑦ Prompt 注入 [来源n]《文件》第x段
  → ⑧ LLM(DeepSeek-V3.2, temp 0.1) → ⑨ QaLogService 审计落库
```

## 关键不变量（改代码前必读）

1. **知识库隔离**：一切按 kb 操作的入口必须先过 `KnowledgeBaseService.requireAccess(kbId)`（404/403）；pgvector 的 SQL 必须带 `kb_id` 过滤（存储层兜底）
2. **双数据源无事务**：MySQL(MP 主数据源) + PG(`@Qualifier("pgJdbcTemplate")`) 不能放一个事务里。PG 操作不要加 `@Transactional`；一致性靠 document 状态机（PARSING/READY/FAILED）+ 失败补偿（`processDocument` 的 catch 里删片段、标 FAILED）
3. **BM25 索引失效**：文档增删、知识库删除后必须 `bm25IndexService.rebuild(kbId)`，否则检索到已删数据（懒加载+整体重建策略）
4. **small-to-big 数据形态**：document_chunk 的 content=子块（检索/向量化对象），parent_content=父块（喂 LLM 用，可空）；heading_path=章节路径、parent_index=父块序号（0=单级模式）；`chunk.parent-size<=size` 时退化为单级分块。老库升级 SQL 见 sql/pgvector_schema.sql 注释
5. **chunk_summary 与 document_chunk 同生命周期**：文档删除/失败补偿/知识库删除必须同时删两张表（summaryDao.deleteByDocId/deleteByKbId），否则摘要树检索会命中已删文档
6. **外部依赖必须降级**：QueryRewrite / Rerank / Embedding / Summary 任一步失败都不能让主链路 500 —— 降级路径：改写→原问题、精排→RRF 序、摘要→无摘要全库检索、兜底→固定话术
7. **流式接口不走 Result 包装**：`/ask/stream` 返回 SseEmitter（message/sources/error 事件），统一异常处理管不到它，错误在 askStream 内部消化
8. **异步上传**：`rag.upload.async=true` 时上传秒返回 PARSING，后台线程处理，轮询 `GET /api/documents` 看状态；MultipartFile 必须在请求线程读成字节数组再交给线程池
9. **多轮会话归属**：conversation 校验必须同时满足 user_id=当前用户 AND kb_id=当前库（跨用户/跨库复用会话 id 要 404）；qa_log.conversation_id 为 NULL 表示单轮
10. **可观测性**：`/actuator/health`、`/actuator/info` 公开；`/actuator/metrics` 仅 ADMIN。改动要过 mvn test 且 GitHub Actions CI 会自动跑

## 技术栈版本坑（锁版本的原因，别乱升级）

- **LangChain4j 1.7**：接口叫 `ChatModel`（不是 0.x 的 `ChatLanguageModel`）；`embed()` 返回 `Response<Embedding>` 要 `.content()`；流式用 `StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)`（onPartialResponse/onCompleteResponse/onError）。网上教程大多是 0.x 旧 API
- **jieba-analysis 1.0.2**：分词方法是 `process(text, SegMode)`，不是 `segment`；SEARCH 模式细分复合词，索引与查询必须同一分词口径（JiebaUtil 统一）
- **jjwt 0.12**：`Jwts.parser().verifyWith(key).build().parseSignedClaims()` 新 API，与 0.11 不兼容
- **pgvector**：HNSW 索引要求 pgvector ≥ 0.5；SQL 用 `<=>` 余弦距离、`CAST(:embedding AS vector)`
- **springdoc 2.6**：Swagger UI 在 `/swagger-ui.html`，JWT 在右上角 Authorize 填
- **双数据源三连坑（真实踩过，勿重蹈）**：① PG 数据源 bean 先于 Boot 自动配置注册会让 MySQL 自动配置退避、MP 的 SQL 全打到 PG → 主数据源必须手动声明 `@Primary`；② `spring.datasource.url` 绑不到 HikariDataSource（setter 叫 jdbcUrl）→ 必须经 `DataSourceProperties` 中转；③ `SqlParameterSourceUtils.createBatch` 会把 MapSqlParameterSource 当 JavaBean 反射 → 批量入库直接传 `SqlParameterSource[]` 给 NamedParameterJdbcTemplate.batchUpdate
- **Lombok is 前缀陷阱**：`private boolean isFallback` 的 getter 是 `isFallback()`，Jackson 会输出 `"fallback"` → 对外字段名必须用 `@JsonProperty("isFallback")` 固定
- **PG DDL 不支持 MySQL 风格行内注释**：`COMMENT '...'` 是语法错误，用 `--`

## 配置与环境

- `application.yml` 按 spring.datasource(MySQL) / pgvector.datasource / rag.* / jwt 分区；`rag.prompt-template` 占位符是 `{context}` `{question}`
- 环境变量：`DASHSCOPE_API_KEY`（必填，**当前装的是硅基流动 key**，变量名沿用旧名）、`MYSQL_HOST/MYSQL_PORT/PG_HOST/PG_PORT`（切 VM 部署用）、`MYSQL_PASSWORD/PG_PASSWORD`、`SERVER_PORT`（8080/8081 常被本机其他 java 项目占用）
- 模型默认走硅基流动 `https://api.siliconflow.cn/v1`：LLM=`deepseek-ai/DeepSeek-V3.2`（免费额度）、Embedding=`BAAI/bge-m3`、Rerank=`BAAI/bge-reranker-v2-m3` + `api-format: siliconflow`（siliconflow 与 dashscope 的 /rerank 请求协议不同，换百炼要同步改 api-format 为 dashscope）
- 已知事实：embedding/rerank 需账户有余额（报 30001 就是没充值）；bge-m3 向量 1024 维，与 pgvector 表结构强绑定，换 embedding 模型必须同步改维度
- **用户偏好**：第三方服务（MySQL/pgvector）放 VMware 虚拟机里的 Docker，不放 Windows 本机；mall-swarm VM(192.168.88.129) 已占用 3306，本项目的 mysql 容器用 3307。涉及部署/连库前先确认用户是否要现在部署

## 实测基准（2026-09-07，VM 部署形态，改动前对照）

- 检索评测 Hit@5=100%（4 文档/41 块（标题感知分块后）/30 标注，2026-09-18 新链路复测，平均相似度 0.712，docs/eval/）；**对比实验（2026-09-20）**：纯向量 90%/纯BM25 100%/无精排 100%/无改写 100%（基线 100%）；全链路冒烟 8/8；模块六测全过；单元测试 20 个
- 多轮对话已实现（conversation 表 + 历史注入），纯指代追问实测通过（9095 端口验证）
- 性能：首问冷启动 ~93s（jieba 词典加载 + BM25 索引构建），稳态 5~7s/问——延迟大头是 Query 改写 + Rerank 两次 LLM 调用，低延迟场景可关 `rag.retrieval.query-rewrite.enabled`
- **压测基线（2026-09-20）**：接口层 `/auth/me` 并发 20 → 3225 QPS / p95 16ms；RAG 全链路 `/search` 并发 5 → QPS 0.8 / avg 6.2s / p99 27.3s（瓶颈=外部模型 API 排队，非服务自身）。压测脚本在临时目录 rag_bench.py，重压时对照此基线
- 兜底阈值 `min-similarity: 0.4` 有数据支撑（命中样本相似度均 >0.54，可收紧到 0.5）
- 运行环境：企业级 VM(192.168.88.130) 需先开机（无 vmrun 无法远程开机，要用户动手）；应用 `SERVER_PORT=9090` + 4 个数据库环境变量启动；改动检索逻辑后以这些基准数做回归对照

## 工作约定（用户要求）

- 中文回复，代码/命令/路径英文；结论先行，不过度铺垫
- 不自动 git commit/push；删除文件、改密钥/连接配置前先问
- 改 RAG 检索相关逻辑（分块/融合/阈值/精排）必须同步跑 `mvn test` 并更新 README 的链路表格
