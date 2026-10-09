# CLAUDE.md — enterprise-RAG

面试向企业知识库 RAG 问答系统。Spring Boot 3.3 + LangChain4j 1.7 + MySQL(业务) + PostgreSQL/pgvector(向量) + 通义千问/BGE-M3(OpenAI 兼容协议)。后端 RESTful + 内置单页前端（Vue3 + Element Plus，无构建，随 jar 托管）。

## 常用命令

```bash
mvn -q compile          # 编译（本机 Maven 已绑定 JDK17；PATH 默认 java 是 1.8，别用 java 命令验证）
mvn test                # 32 个单元测试（标题分块/BM25/命中词/RRF 融合/多查询/摘要范围检索/父块展开/分词/限流/知识库路由/流式错误通道），改检索逻辑必跑
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
  ⓪ 路由(仅 /api/ask 统一入口): LLM 从全部知识库(list 白名单)选 ≤3 个最相关库融合检索;
     单库直通、无候选 400、调用失败降级全部库; 旧 /api/kb/{kbId}/ask 不路由
  ① QueryRewriteService 多查询改写(失败降级原问题) → ② 摘要树检索: SummaryDao 搜摘要 Top3 定
     (doc_id,parent_index) 范围 → 范围内 VectorStoreDao 向量 Top10(摘要为空降级全库) + BM25 Top10(含命中词)
  → ③ RRF(k=60) 跨查询累积 → ④ RerankService gte-rerank 精排(失败降级 RRF 序)
  → ⑤ 父级块展开(small-to-big: 子块换父块文本,同父块去重留高分;保留 headingPath/matchedTerms)
  → ⑥ 兜底判定(maxSimilarity<0.4 不调 LLM) → ⑦ Prompt 注入 [来源n]《文件》第x段
  → ⑧ LLM(DeepSeek-V3.2, temp 0.1; 流式：首个 token 前连接断开则自动重试一次) → ⑨ QaLogService 审计落库
```

## 关键不变量（改代码前必读）

1. **知识库权限（读写分权）**：读入口（问答/文档列表/详情/检索）过 `KnowledgeBaseService.requireRead(kbId)`（仅 404）——对所有登录用户开放；管理入口（建库/删库/传文档/删文档/日志审计）过 `requireManage(kbId)`（404/403）——仅 owner 或 ADMIN，建库再限 ADMIN；pgvector 的 SQL 必须带 `kb_id` 过滤（存储层兜底）；统一问答路由候选来自 `list()`（全部库），pgvector SQL 仍强制 `kb_id IN` 过滤
2. **双数据源无事务**：MySQL(MP 主数据源) + PG(`@Qualifier("pgJdbcTemplate")`) 不能放一个事务里。PG 操作不要加 `@Transactional`；一致性靠 document 状态机（PARSING/READY/FAILED）+ 失败补偿（`processDocument` 的 catch 里删片段、标 FAILED）
3. **BM25 索引失效**：文档增删、知识库删除后必须 `bm25IndexService.rebuild(kbId)`，否则检索到已删数据（懒加载+整体重建策略）
4. **small-to-big 数据形态**：document_chunk 的 content=子块（检索/向量化对象），parent_content=父块（喂 LLM 用，可空）；heading_path=章节路径、parent_index=父块序号（0=单级模式）；`chunk.parent-size<=size` 时退化为单级分块。老库升级 SQL 见 sql/pgvector_schema.sql 注释
5. **chunk_summary 与 document_chunk 同生命周期**：文档删除/失败补偿/知识库删除必须同时删两张表（summaryDao.deleteByDocId/deleteByKbId），否则摘要树检索会命中已删文档
6. **外部依赖必须降级**：QueryRewrite / Rerank / Embedding / Summary 任一步失败都不能让主链路 500 —— 降级路径：改写→原问题、精排→RRF 序、摘要→无摘要全库检索、兜底→固定话术
7. **流式接口有两条错误通道**：`/ask/stream` 的 SseEmitter（message/sources/error 事件）只兜成功路径和 LLM 调用中的异常；`requireRead`/`checkAsk`/会话解析/路由/检索等步骤都在首个事件**之前**同步完成（此时响应未提交），异常照走 GlobalExceptionHandler 返回 JSON —— 所以异常响应必须显式 `contentType(application/json)`，`produces=text/event-stream` 协商不出 JSON 时会退化成 500 空响应体
8. **异步线程拿不到请求线程的上下文**：① `rag.upload.async=true` 时上传秒返回 PARSING，后台线程处理，轮询 `GET /api/documents` 看状态；MultipartFile 必须在请求线程读成字节数组再交给线程池。② SSE 回调（onCompleteResponse/onError）跑在 langchain4j 的 ForkJoinPool 线程上，SecurityContext ThreadLocal 为空 —— userId 必须在请求线程取好传进 `qaLogService.save`，不能在回调里现调 `SecurityUtil.currentUser()`
9. **ASYNC/ERROR 派发必须放行**：SseEmitter 完成后容器会做 ASYNC 回派，`JwtAuthFilter` 是 OncePerRequestFilter（默认跳过异步派发），而 Spring Security 6 的 AuthorizationFilter 默认过滤所有 dispatcher type → SecurityConfig 里要 `dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`，否则每次流式请求刷 AccessDeniedException；限流配额 `/ask` 与 `/ask/stream` 共用（10/min）
10. **多轮会话归属**：conversation 校验必须同时满足 user_id=当前用户 AND kb_id=当前库（跨用户/跨库复用会话 id 要 404）；qa_log.conversation_id 为 NULL 表示单轮；统一会话 kb_id=NULL（只校验 user_id），旧按库接口遇 NULL 会话返回 404
11. **可观测性**：`/actuator/health`、`/actuator/info` 公开；`/actuator/metrics` 仅 ADMIN。改动要过 mvn test 且 GitHub Actions CI 会自动跑

## 技术栈版本坑（锁版本的原因，别乱升级）

- **LangChain4j 1.7**：接口叫 `ChatModel`（不是 0.x 的 `ChatLanguageModel`）；`embed()` 返回 `Response<Embedding>` 要 `.content()`；流式用 `StreamingChatModel.chat(ChatRequest, StreamingChatResponseHandler)`（onPartialResponse/onCompleteResponse/onError）。网上教程大多是 0.x 旧 API
- **jieba-analysis 1.0.2**：分词方法是 `process(text, SegMode)`，不是 `segment`；SEARCH 模式细分复合词，索引与查询必须同一分词口径（JiebaUtil 统一）
- **jjwt 0.12**：`Jwts.parser().verifyWith(key).build().parseSignedClaims()` 新 API，与 0.11 不兼容
- **pgvector**：HNSW 索引要求 pgvector ≥ 0.5；SQL 用 `<=>` 余弦距离、`CAST(:embedding AS vector)`
- **springdoc 2.6**：Swagger UI 在 `/swagger-ui.html`，JWT 在右上角 Authorize 填
- **双数据源三连坑（真实踩过，勿重蹈）**：① PG 数据源 bean 先于 Boot 自动配置注册会让 MySQL 自动配置退避、MP 的 SQL 全打到 PG → 主数据源必须手动声明 `@Primary`；② `spring.datasource.url` 绑不到 HikariDataSource（setter 叫 jdbcUrl）→ 必须经 `DataSourceProperties` 中转；③ `SqlParameterSourceUtils.createBatch` 会把 MapSqlParameterSource 当 JavaBean 反射 → 批量入库直接传 `SqlParameterSource[]` 给 NamedParameterJdbcTemplate.batchUpdate
- **Lombok is 前缀陷阱**：`private boolean isFallback` 的 getter 是 `isFallback()`，Jackson 会输出 `"fallback"` → 对外字段名必须用 `@JsonProperty("isFallback")` 固定
- **PG DDL 不支持 MySQL 风格行内注释**：`COMMENT '...'` 是语法错误，用 `--`

## 前端（src/main/resources/static，无构建）

Vue3 + Element Plus 单页应用，5 个库文件锁定在 `lib/`（离线可跑）；浏览器直接开 `http://localhost:9090/`。组件：login-view / kb-panel / chat-panel / doc-panel。

- **SSE 不能用原生 EventSource**：接口是 POST 且要 Authorization 头 → `js/api.js` 用 fetch + ReadableStream 手写帧解析
- **多条 `data:` 行必须用 `\n` 拼回**：token 里的换行会被 Spring 转义成多行，只取第一行会把回答截断
- **`ElMessage`/`ElMessageBox` 要在 app.js 开头显式转挂**：Element Plus 的 UMD 只导出 `window.ElementPlus`，不创建全局函数（踩过：ReferenceError 卡死上传对话框）
- 聊天窗口：单一 `store.chat`（mode=auto 自动选库 / kb 指定知识库），auto 走 `POST /api/ask/stream`、kb 走 `POST /api/kb/{kbId}/ask/stream`；切模式/切库自动开新会话（后端会话归属校验不允许跨用）；`meta` 事件回传 conversationId，追问时带回去复用会话；来源条目带库名标签
- 静态资源实际从 `target/classes` 提供：CLI 改 js/css 后需 `mvn process-resources`（IDE 开自动构建时它会代劳）才生效；浏览器刷新即可，不用重启

## 配置与环境

- `application.yml` 按 spring.datasource(MySQL) / pgvector.datasource / rag.* / jwt 分区；`rag.prompt-template` 占位符是 `{context}` `{question}`
- 环境变量：`DASHSCOPE_API_KEY`（必填，**已用 setx 写入用户环境变量**，新终端自动生效；值装的是硅基流动 key）。数据库地址/端口**已写死**在 application.yml（VM 192.168.88.130，MySQL 3306/PG 5432，应用端口 9090）——部署到新环境时改 yml；密码可经 MYSQL_PASSWORD/PG_PASSWORD 覆盖（默认 123456）
- 模型默认走硅基流动 `https://api.siliconflow.cn/v1`：LLM=`deepseek-ai/DeepSeek-V3.2`（免费额度）、Embedding=`BAAI/bge-m3`、Rerank=`BAAI/bge-reranker-v2-m3` + `api-format: siliconflow`（siliconflow 与 dashscope 的 /rerank 请求协议不同，换百炼要同步改 api-format 为 dashscope）
- 已知事实：embedding/rerank 需账户有余额（报 30001 就是没充值）；bge-m3 向量 1024 维，与 pgvector 表结构强绑定，换 embedding 模型必须同步改维度
- **用户偏好**：第三方服务（MySQL/pgvector）放 VMware 虚拟机里的 Docker，不放 Windows 本机；mall-swarm VM(192.168.88.129) 已占用 3306，本项目的 mysql 容器用 3307。涉及部署/连库前先确认用户是否要现在部署

## 实测基准（2026-09-07，VM 部署形态，改动前对照）

- 检索评测 Hit@5=100%（4 文档/41 块（标题感知分块后）/30 标注，2026-09-18 新链路复测，平均相似度 0.712，docs/eval/）；**对比实验（2026-09-20）**：纯向量 90%/纯BM25 100%/无精排 100%/无改写 100%（基线 100%）；全链路冒烟 8/8；模块六测全过；单元测试 32 个
- 多轮对话已实现（conversation 表 + 历史注入），纯指代追问实测通过（9095 端口验证）
- **统一问答路由实测（2026-10-01，9091）**：ChatLanguageModel/embed()/阈值 等问命中 2-3 库（来源带库名）、追问复用会话、新对话开新会话；无关问题走 LLM 层二次兜底（isFallback=true、来源非空、非 500）
- 性能：首问冷启动 ~93s（jieba 词典加载 + BM25 索引构建），稳态 5~7s/问——延迟大头是 Query 改写 + Rerank 两次 LLM 调用，低延迟场景可关 `rag.retrieval.query-rewrite.enabled`
- **压测基线（2026-09-20）**：接口层 `/auth/me` 并发 20 → 3225 QPS / p95 16ms；RAG 全链路 `/search` 并发 5 → QPS 0.8 / avg 6.2s / p99 27.3s（瓶颈=外部模型 API 排队，非服务自身）。压测脚本在临时目录 rag_bench.py，重压时对照此基线
- 兜底阈值 `min-similarity: 0.4` 有数据支撑（命中样本相似度均 >0.54，可收紧到 0.5）
- 运行环境：企业级 VM(192.168.88.130) 需先开机（无 vmrun 无法远程开机，要用户动手）；应用 `SERVER_PORT=9090` + 4 个数据库环境变量启动；改动检索逻辑后以这些基准数做回归对照

## 工作约定（用户要求）

- 中文回复，代码/命令/路径英文；结论先行，不过度铺垫
- 不自动 git commit/push；删除文件、改密钥/连接配置前先问
- 改 RAG 检索相关逻辑（分块/融合/阈值/精排）必须同步跑 `mvn test` 并更新 README 的链路表格
