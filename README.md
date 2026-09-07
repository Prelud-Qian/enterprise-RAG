# enterprise-RAG — 企业知识库 RAG 问答系统

基于 SpringBoot 3 + LangChain4j + pgvector 的面试向企业知识库问答系统，覆盖 RAG 完整链路：**文档解析 → 语义分块 → 向量化入库 → 混合检索（向量 + BM25）→ Prompt 组装 → LLM 生成 → 溯源审计**，附带幻觉兜底与 RBAC 数据隔离。

## 目录

- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [RAG 核心链路](#rag-核心链路)
- [快速开始](#快速开始)
- [接口清单](#接口清单)
- [技术选型对比（面试素材）](#技术选型对比面试素材)
- [坑与优化点（面试素材）](#坑与优化点面试素材)
- [项目结构](#项目结构)

## 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Spring Boot 3.3.5 / JDK 17 |
| RAG 框架 | LangChain4j 1.7.0（模型接入层） |
| 业务库 | MySQL 8.0 + MyBatis-Plus 3.5.7 |
| 向量库 | PostgreSQL + pgvector（HNSW 索引，手写 JDBC SQL） |
| 文档解析 | Apache Tika 2.9（PDF / Word） |
| 关键词检索 | jieba 分词 + 手写 BM25 内存倒排索引 |
| 模型 | DeepSeek-V3.2（LLM）+ BGE-M3（Embedding，1024 维）+ BGE-Reranker-v2-M3（精排），经硅基流动 OpenAI 兼容协议接入，换模型只改 yml |
| 安全 | Spring Security + JWT（jjwt 0.12），简单 RBAC（USER/ADMIN） |

## 系统架构

```
                          ┌─────────────────────────────────────┐
   Postman / 前端 ───────►│  Spring Security (JWT 无状态 + RBAC) │
                          └──────────────┬──────────────────────┘
                                         │
                          ┌──────────────▼──────────────────────┐
                          │ Controller: Auth / KB / Document /  │
                          │             Qa / Admin              │
                          └──────────────┬──────────────────────┘
                                         │
   ┌─────────────── 上传链路 ─────────────┼───────────── 问答链路 ─────────────────┐
   │                                     │                                        │
   │  DocumentService                    │  QaService                             │
   │   ① Tika 解析(不落盘)               │   ① RetrievalService 混合检索           │
   │   ② ChunkingService 语义分块        │      ├ 向量召回: pgvector <=> 余弦 TopK │
   │   ③ EmbeddingService 批量向量化     │      ├ BM25召回: 内存倒排索引 TopK      │
   │      (BGE-M3, 批量20+重试)          │      ├ RRF 融合去重 → 候选集           │
   │   ④ VectorStoreDao 批量入库         │      └ Rerank 精排: gte-rerank → Top5  │
   │   ⑤ 重建 BM25 索引                  │   ② 幻觉兜底: 相似度<阈值 直接拒绝回答  │
   │                                     │   ③ Prompt 组装: 注入来源片段           │
   │                                     │   ④ LLM 调用: DeepSeek-V3.2 (temp=0.1) │
   │                                     │   ⑤ QaLogService 审计落库              │
   └──────────────────┬──────────────────┴────────────────┬──────────────────────┘
                      │                                     │
          ┌───────────▼──────────┐              ┌───────────▼──────────┐
          │  MySQL 8.0（业务库）  │              │ PostgreSQL + pgvector │
          │  sys_user            │              │  document_chunk       │
          │  knowledge_base      │              │  (原文+向量+溯源元数据) │
          │  document            │              │  HNSW 余弦索引         │
          │  qa_log              │              └──────────────────────┘
          └──────────────────────┘
```

**知识库隔离设计**：`knowledge_base.owner_id` 是隔离核心，所有按知识库操作的 Service 入口统一走 `KnowledgeBaseService.requireAccess()`（非 owner 且非 ADMIN → 403）；pgvector 检索 SQL 强制带 `kb_id` 过滤，存储层兜底。

## RAG 核心链路

代码内每步都有详细注释，对应文件：

| 步骤 | 代码位置 |
|---|---|
| ① 文档解析 | `DocumentService.parseText()` — Tika 自动识别 PDF/Word，writeLimit 防 OOM，不落盘 |
| ② 语义分块 | `ChunkingService.chunk()` — 优先段落/句子边界切分，超限硬切，相邻块 overlap |
| ③ 向量化 | `EmbeddingService.embedBatch()` — BGE-M3 批量调用（默认 20/批），2 次重试 + 维度校验 |
| ④ 入库 | `VectorStoreDao.insertBatch()` — pgvector 批量 INSERT，kb_id/doc_id/chunk_index 元数据 |
| ④.5 Query 改写 | `QueryRewriteService.rewrite()` — LLM 把问题改写成多个检索查询（失败降级原始问题），RRF 跨查询累积 |
| ⑤ 混合检索 | `RetrievalService.retrieve()` — 每个查询 向量 Top10 + BM25 Top10 → RRF(k=60) 融合 → 候选集 |
| ⑤.5 精排 | `RerankService.rerank()` — gte-rerank 交叉编码器重排序取 Top5，失败自动降级 RRF 顺序 |
| ⑤.6 父级块展开 | `RetrievalService.expandToParents()` — small-to-big：命中的子块展开为父级块喂给 LLM，同父块去重 |
| ⑥ 幻觉兜底 | `QaService.ask()` — 最佳相似度 < 0.4 或无召回 → 不调 LLM，直接返回固定话术 |
| ⑦ Prompt + LLM | `QaService.ask()` — 模板注入 `[来源n]《文件名》第x段`，temperature 0.1 |
| ⑧ 审计 | `QaLogService.save()` — 问题/答案/上下文/来源/耗时全量落库 |

BM25 实现见 `Bm25IndexService`：jieba SEARCH 模式分词 → 内存倒排索引（term → postings）→ BM25 公式（k1=1.5, b=0.75），索引按知识库懒加载、文档变更后整体重建。

## 检索评测（面试数据来源）

`docs/eval/` 提供检索评测脚本（Python3 标准库，无需 pip），流程：

1. 传完文档后，准备标注集 `docs/eval/eval_set.jsonl`，每行一条：`{"question": "问题", "docId": 预期文档id, "chunkIndex": 预期片段序号}`
2. 登录拿 token，运行：
   ```bash
   python docs/eval/eval.py --token <JWT> --kb 1 --k 5
   ```
3. 输出每条问题的 HIT/MISS 明细 + 汇总报告：**Hit@5 命中率、平均 maxSimilarity、平均检索耗时**

拿到数据后可以做三组对比实验（面试核心素材）：
- 纯向量 vs 纯 BM25 vs 混合检索的 Hit@5 对比（验证混合检索价值）
- 不同分块大小/重叠度对比（回答"分块参数怎么定的"）
- 开启/关闭 Rerank 精排对比（回答"精排带来了多少提升"）

`maxSimilarity` 的分布也是调 `rag.retrieval.min-similarity` 兜底阈值的依据：把标注集里 MISS 样本的相似度上界作为阈值参考。

## 快速开始

> ✅ 2026-09-07 全链路冒烟 8/8 通过（注册/登录/建库/上传 docx/问答溯源/幻觉兜底/仅检索/审计日志），部署形态为方式二（VM 内存储 + 本机应用）。

### 方式一：Docker Compose 一键环境（推荐，无需本地装数据库）

```bash
# 设置模型 API Key（硅基流动，必填）
set DASHSCOPE_API_KEY=sk-xxx            # Windows
export DASHSCOPE_API_KEY=sk-xxx         # Linux/Mac

docker compose up -d --build
```

自动完成：起 MySQL 8 + pgvector（内置 vector 扩展）两个容器并执行建表脚本，再构建并启动应用容器。应用启动后直接调 `http://localhost:8080`。

### 方式二：服务放 VMware 虚拟机（实际部署形态，已跑通）

> 实际部署记录：enterprise-RAG VM（192.168.88.130，Mall 克隆机）承担全部存储。
> - **MySQL 8**：VM 内 Docker 容器（mysql:latest，3306），root 密码 123456，`enterprise_rag` 业务库已建（`sql/mysql_schema.sql` 已导入）
> - **PostgreSQL 15 + pgvector**：原生安装（Docker Hub 被墙拉不动 pgvector 镜像，改用阿里镜像 PGDG yum 源：EPEL 装 libzstd → `postgresql15-server` + `pgvector_15`），postgres 密码 123456，`enterprise_rag` 库 + vector 扩展 + HNSW 索引已建（`sql/pgvector_schema.sql` 已导入）
> - **模型**：硅基流动（`https://api.siliconflow.cn/v1`），LLM=deepseek-ai/DeepSeek-V3.2；embedding/rerank 需账户有余额

```bash
# Windows 侧：指向 VM 后启动应用（8080/8081 若被占，用 SERVER_PORT 换端口）
set MYSQL_HOST=192.168.88.130
set MYSQL_PORT=3306
set PG_HOST=192.168.88.130
set PG_PORT=5432
set DASHSCOPE_API_KEY=sk-xxx
set SERVER_PORT=18888
mvn spring-boot:run
```

> 从零复现 VM 部署（新环境时参考）：mysql 走 `docker run -d --name rag-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=123456 mysql:8.0`；PG 原生安装命令见上文描述，或改用 docker-compose（见方式一，需网络可达 Docker Hub 或配置镜像加速）。

### 方式三：本地环境（不用 Docker）

1. **建库建表**：执行 `sql/mysql_schema.sql`（MySQL 8.0）与 `sql/pgvector_schema.sql`（PostgreSQL 14+，需自行安装 pgvector 扩展——Windows 编译安装较麻烦，也可只让 PG 走 Docker：`docker run -d --name pgvector -p 5432:5432 -e POSTGRES_PASSWORD=123456 pgvector/pgvector:pg16`）
2. **配置**：`application.yml` 中修改 MySQL/PG 连接；设置模型 API Key：
   ```bash
   set DASHSCOPE_API_KEY=sk-xxx   # Windows（硅基流动 key）
   export DASHSCOPE_API_KEY=sk-xxx  # Linux/Mac
   ```
   模型配置在 `rag.*` 段，默认硅基流动（LLM=DeepSeek-V3.2 / Embedding=BAAI/bge-m3 / Rerank=BAAI/bge-reranker-v2-m3）；换阿里云百炼只需改 base-url + model + `rag.rerank.api-format: dashscope`
3. **启动**：
   ```bash
   mvn spring-boot:run
   ```
4. **调接口**：见 [docs/postman-examples.md](docs/postman-examples.md)，含上传、问答溯源、流式、兜底、RBAC 越权全套演示；或打开 Swagger UI `http://localhost:8080/swagger-ui.html` 在线调试

## 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录，返回 JWT |
| GET | /api/auth/me | 当前用户 |
| POST | /api/kb | 创建知识库 |
| GET | /api/kb | 我的知识库列表 |
| GET | /api/kb/{id} | 知识库详情 |
| DELETE | /api/kb/{id} | 删除（级联） |
| POST | /api/documents/upload | 上传 PDF/Word（multipart：file, kbId, 可选 chunkSize/chunkOverlap） |
| GET | /api/documents?kbId= | 文档分页列表 |
| DELETE | /api/documents/{id} | 删除文档（级联向量+重建索引） |
| POST | /api/kb/{kbId}/ask | 提问 → 回答 + 引用来源 + 兜底标记 |
| POST | /api/kb/{kbId}/ask/stream | 流式提问（SSE 逐 token 返回） |
| POST | /api/kb/{kbId}/search | 仅检索不生成（调试/评测用） |
| GET | /api/kb/{kbId}/qa-logs | 问答日志分页（审计） |
| GET | /api/admin/users | 用户列表（仅 ADMIN，RBAC 验证） |

> 启动后可打开 Swagger UI 在线调试全部接口：`http://localhost:8080/swagger-ui.html`（右上角 Authorize 填 JWT）

## 技术选型对比（面试素材）

### 1. 向量库：pgvector vs Milvus / Chroma

- **选 pgvector 的理由**：数据量百万级向量以内性能足够（HNSW 索引）；不需要额外运维一套独立中间件；向量与元数据同库，检索时直接 JOIN，还能用 SQL 事务/备份生态；面试能讲清 `<=>` 余弦距离、HNSW/IVFFlat 原理
- **Milvus**：十亿级向量、分布式场景才需要，单机部署成本高（依赖 etcd/MinIO）
- **Chroma**：Python 生态，Java SDK 不成熟，生产化程度低

### 2. 文档解析：Tika vs 直接用 PDFBox / POI

- Tika 统一入口：`AutoDetectParser` 自动识别格式，以后要加 HTML/Markdown/PPT 零成本
- 直接 PDFBox 只能解 PDF，POI 只能解 Office，每个格式写一套代码
- Tika 坑：依赖树庞大（打包体积大）；扫描件 PDF 无文本层需 OCR（本系统明确不支持）

### 3. 二阶段检索：召回 + Rerank 精排 vs 单路召回直出

- 召回（BGE-M3 双塔 + BM25）速度快但精度有限，Top10 里噪声多；用交叉编码器 gte-rerank 对"问题+片段"逐对打分重排序后，Top5 相关性显著提升 —— 这是当前 RAG 生产系统标配
- 精排按 token 计费，所以只对 RRF 候选前 20 条打分（成本控制）
- 精排服务故障时自动降级回 RRF 顺序，检索可用性不依赖精排

### 4. 关键词检索：内存倒排 + 手写 BM25 vs Elasticsearch

- 零额外中间件，代码 200 行讲清 BM25 公式与倒排结构，面试展示理解深度
- ES 的 BM25 生产级但多一套集群运维，且与固定技术栈（MySQL+PG）冲突
- 明确边界：chunk 数超过十万级或需要多副本高可用时，迁移 pg_search（ParadeDB）或 ES，索引结构与检索接口已抽象（`Bm25IndexService`），替换成本低

### 5. 向量库操作：手写 JdbcTemplate vs LangChain4j PgVectorEmbeddingStore

- 框架封装表结构固定（metadata 塞 JSONB），按知识库隔离的过滤能力弱，且面试时讲不清底层 SQL
- 手写 SQL：表结构自控（kb_id 隔离列、doc_id 溯源列独立索引），能演示 HNSW 建索引、`<=>` 算子、批量写入优化
- LangChain4j 在项目中只做模型接入层（ChatLanguageModel / EmbeddingModel），职责单一

### 6. 模型接入：OpenAI 兼容协议 vs 各家原生 SDK

- 本项目当前用硅基流动一个 key 覆盖 LLM（DeepSeek-V3.2）+ Embedding（BGE-M3）+ Rerank（BGE-Reranker-v2-M3）；阿里云百炼同样是 OpenAI 兼容端点（qwen-plus + gte-rerank），RerankService 已做双协议兼容（`api-format` 切换）
- base-url 可配置，换硅基流动/本地 vLLM/Ollama 等任何兼容端点零代码改动 —— 这就是协议标准化的价值
- LangChain4j 的 dashscope 原生模块也可用，但只覆盖千问、API 随版本变动大

## 坑与优化点（面试素材）

### 踩过的坑

1. **LangChain4j 0.36 → 1.x API 断裂**：`generate()` 变成 `chat(ChatRequest)`，Embedding 返回值包了一层 `Response<T>`。用 1.x 前要先查迁移文档，网上大量教程还是旧 API
2. **双数据源装配冲突**：PG 若也用 `spring.datasource.*` 前缀会被 Boot 自动装配抢走。解法：自定义 `pgvector.*` 前缀 + 手建 HikariDataSource + NamedParameterJdbcTemplate
3. **双库无分布式事务**：MySQL 业务表与 PG 向量库无法单事务。解法：文档状态机（PARSING/READY/FAILED）+ 失败补偿（异常时清理已入库片段），保证不产生"有片段无文档"的脏数据；量大后可演进 Seata/本地消息表
4. **中文分词决定 BM25 效果**：jieba 默认模式会把"知识库"切成一个词，SEARCH 模式细分（知识/库），召回率更好；专业领域词（如"数字孪生"）需要自定义词典
5. **向量批处理限流**：一次 embedAll 太多片段会触发 API 限流/超时。解法：批量 20 + 2 次重试指数退避；生产上应异步化 + 消息队列削峰
6. **BGE-M3 相似度阈值不好拍**：0.4 是经验值，与文档领域强相关。正确做法：用少量标注问答集评估 top-k 召回相关率，反推阈值
7. **Tika 解析超大文档 OOM**：BodyContentHandler 必须设 writeLimit，否则一个 500 页 PDF 可能打爆堆
8. **pgvector 版本差异**：HNSW 索引需要 pgvector ≥ 0.5.0，旧版本只能用 IVFFlat；老环境部署先查版本
9. **双数据源装配三连坑（真实踩过，运行时才爆）**：① pgDataSource 先于 Boot 自动配置注册 → MySQL 自动配置因 `@ConditionalOnMissingBean` 退避，MP 的 SQL 全打到 PG（报 relation 不存在）→ 手动声明 `@Primary` 主数据源；② `spring.datasource.url` 直接绑不到 HikariDataSource（setter 叫 `jdbcUrl`）→ 经 `DataSourceProperties` 中转；③ `SqlParameterSourceUtils.createBatch` 在 Spring 6.1 会把 `MapSqlParameterSource` 当 JavaBean 反射 → 直接传数组给 `NamedParameterJdbcTemplate.batchUpdate`
10. **Lombok + Jackson 的 is 前缀陷阱**：`private boolean isFallback` 生成的 getter 是 `isFallback()`，Jackson 会序列化成 `"fallback"` 而非 `"isFallback"` → 用 `@JsonProperty` 固定字段名
11. **PG DDL 不能用 MySQL 风格行内注释**：`COMMENT '...'` 在 PG 里是语法错误，要用 `--` 注释

### 优化方向（面试可说）

1. **混合检索融合调优**：RRF 常数 k 与两路 TopK 配比可离线调参；Rerank 候选数 topN 是成本与效果的平衡点；进阶可换加权融合/学习排序（LTR）
2. **BM25 索引演进**：懒加载全量重建 → 增量更新；加读写锁/双 buffer 替换去掉 synchronized 串行；超大规模迁 pg_search/ES
3. **文档入库异步化**：已内置 `rag.upload.async=true`（线程池 + 状态轮询）；生产可演进为消息队列 + 任务表，支持失败重试与进度查询
4. **检索缓存**：高频问题缓存结果；知识库只读时段可预热 BM25 索引
5. **多路召回扩展**：问题改写（QueryRewriteService）、父级块检索 small-to-big（子块检索、父块喂 LLM）均已实现；剩余方向：表格/多模态解析、按文档结构（标题层级）分块
6. **评测体系**：`docs/eval/` 评测脚本 + 标注集，量化对比分块策略/阈值/融合参数的效果 —— 这是 RAG 工程的核心方法论
7. **安全加固**：JWT 加 refresh token / 黑名单；上传文件杀毒；Prompt 注入防护；问答接口限流

## 项目结构

```
enterprise-RAG
├── docker-compose.yml            # 一键环境：MySQL 8 + pgvector + 应用
├── Dockerfile                    # 多阶段构建（Maven 编译 → JRE 运行）
├── sql/                          # 建表脚本（MySQL 业务库 + PG 向量库）
├── docs/
│   ├── postman-examples.md       # Postman/curl 接口示例（含 RBAC 越权演示）
│   └── eval/                     # 检索评测：eval.py + 标注集 eval_set.jsonl
├── src/main/java/com/enterprise/rag
│   ├── common/                   # Result 统一返回 / BusinessException / 全局异常处理
│   ├── config/                   # Security/JWT/双数据源/模型 Bean/rag.* 配置类
│   ├── controller/               # Auth / KnowledgeBase / Document / Qa / Admin
│   ├── service/                  # RAG 全链路业务（分块/向量化/BM25/混合检索/精排/问答编排）
│   ├── dao/
│   │   ├── mapper/               # MyBatis-Plus Mapper（MySQL）
│   │   └── pg/                   # VectorStoreDao 手写 SQL（pgvector）
│   ├── entity/                   # 实体 + dto/ + vo/
│   └── util/                     # SecurityUtil / JiebaUtil
├── src/test/java/...             # 单元测试（分块/BM25/RRF 融合/分词）
└── src/main/resources/application.yml
```
