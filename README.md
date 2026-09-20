# enterprise-RAG — 企业知识库 RAG 问答系统

基于 SpringBoot 3 + LangChain4j + pgvector 的面试向企业知识库问答系统，覆盖 RAG 完整链路：**文档解析 → 标题感知两级分块（章节溯源）→ 父块摘要树 → 向量化入库 → 混合检索（摘要定范围 + 向量 + BM25 → RRF → 精排）→ Prompt 组装 → LLM 生成 → 溯源审计**，附带幻觉兜底与 RBAC 数据隔离。

## 学习指南

> 零基础入门这份代码库：[docs/learning-guide.md](docs/learning-guide.md) —— 10 天路线，每天 = 大白话概念 → 代码逐方法导读 → 动手实验 → 面试问答，附执行手册与 20 题速查表。

## 项目背景（业务叙事）

企业内部制度文档（员工手册、报销制度、考核办法、信息安全规范等）数量多、更新频繁，传统方式靠 OA 附件 + 人肉搜索，查一条制度要翻十几份文件。本项目把制度文档向量化入库，员工用自然语言提问即可获得**带出处溯源的答案**——检索链路全自研（BM25 / 向量 / 精排 / 摘要树），答案可定位到具体章节条款，检索不到时明确拒绝回答（防幻觉）。技术上以展示 RAG 全链路为目的，业务上可直接作为企业知识库问答底座。

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

**文档入库（离线流程）**：

```
上传请求
  → ① 文件校验（扩展名白名单/大小/文件名清洗）
  → ② Tika 解析（字节流不落盘，writeLimit 防 OOM）
  → ③ 标题感知两级分块（第X章/条为边界，块带章节路径与父块序号，small-to-big）
  → ④ LLM 生成父块摘要（RAPTOR 简化版，单块失败自动跳过）
  → ⑤ BGE-M3 批量向量化（子块+摘要统一批次，20/批 + 重试 + 维度校验）
  → ⑥ pgvector 批量入库（子块→document_chunk，摘要→chunk_summary）
  → ⑦ BM25 索引重建（懒加载，文档变更后整体重建）
  → ⑧ 状态机置 READY（失败 → 删片段+摘要 + 标 FAILED 补偿）
```

**问答（在线 RAG 链路）**：

```
提问
  → ① 权限校验（requireAccess：kb 与用户绑定，检索前拦截）
  → ② Query 改写（LLM 多查询，失败降级原问题）
  → ③ 摘要树检索（先搜父块摘要定范围 → 范围内子块向量 Top10）
       + BM25 Top10（含命中词记录）→ RRF 跨查询融合
  → ④ Rerank 精排（gte-rerank，失败降级 RRF 顺序）
  → ⑤ 父块展开（子块换父块文本，同父块去重留高分）
  → ⑥ 幻觉兜底（maxSimilarity < 0.4 → 固定话术，不调 LLM）
  → ⑦ Prompt 组装（注入 [来源n]《文件名》章节路径）
  → ⑧ LLM 生成（DeepSeek-V3.2，temp 0.1）
  → ⑨ 审计落库（qa_log：问题/答案/上下文/来源/耗时）
```

代码内每步都有详细注释，对应文件：

| 步骤 | 代码位置 |
|---|---|
| ① 文档解析 | `DocumentService.parseText()` — Tika 自动识别 PDF/Word，writeLimit 防 OOM，不落盘 |
| ② 语义分块 | `ChunkingService.chunkStructured()` — 标题感知两级分块：第X章/条强制断块、块带章节路径（heading_path）与父块序号（parent_index）、标题块跳过 overlap、父块 2000/子块 500 small-to-big |
| ②.5 父块摘要 | `SummaryService.summarize()` — LLM 为每个父块生成一句话摘要（RAPTOR 简化版），失败单块跳过 |
| ③ 向量化 | `EmbeddingService.embedBatch()` — BGE-M3 批量调用（子块+摘要统一批次，20/批），2 次重试 + 维度校验 |
| ④ 入库 | `VectorStoreDao.insertBatch()` + `SummaryDao.insertBatch()` — 子块入 document_chunk（含章节路径/父块序号/摘要），摘要入 chunk_summary |
| ④.5 Query 改写 | `QueryRewriteService.rewrite()` — LLM 把问题改写成多个检索查询（失败降级原始问题），RRF 跨查询累积 |
| ⑤ 混合检索 | `RetrievalService.retrieve()` — 摘要树检索（先搜摘要定父块范围→范围内子块向量 Top10）+ BM25 Top10（含命中词）→ RRF(k=60) 融合 → 候选集 |
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

**已跑通的实测结果**（2026-09-07，见 [docs/eval/eval_report.md](docs/eval/eval_report.md)）：4 份仿真制度文档 → 8 子块 → 30 条标注问答 → **Hit@5 = 100%**，平均相似度 0.654。语料 docx 在 `docs/eval/corpus/`，`generate_corpus.py` 一键生成并上传，整套评测可复现。

## 实测验证记录（2026-09-07，VM 部署形态）

**① 文档解析与分块**（同一考勤制度 docx 按三组分块参数上传对比）：

| 文件 | chunkSize/overlap | 分块数 |
|---|---|---|
| Word | 500/50（默认） | 1 |
| Word | 300/30 | 2 |
| Word | 800/80 | 1 |
| 中文 PDF | 500/50 | 1（PDF 解析链路正常） |

**② 向量入库校验**（pgvector 直查）：5 行全部 1024 维、零向量 0、唯一约束 (doc_id, chunk_index) 无冲突、overlap 重叠文本在库内可见。

**③ 混合检索**（三类提问均命中，精排分 0.89~0.96）：关键词精确型（"补卡次数一个月不能超过几次？"）、语义改写型（"上班忘了打卡怎么办？"，字面无"补卡"）、否定句型（"没提前申请算加班吗？"）。

**④ 问答全链路**（提问→检索→Prompt→LLM→答案+引用）：答案准确并带【来源n】标注；qa_log 审计表的 `retrieved_context` 可还原注入 Prompt 的上下文；引用片段返回父块展开全文（small-to-big 生效证据）。

**⑤ 幻觉兜底**：3 个库外问题（比特币/周边餐厅/薛定谔方程）全部返回固定话术、isFallback=true、零引用——答案与话术一字不差，证明未经过 LLM 直接短路。

**⑥ 性能**：首问冷启动 ~93s（jieba 词典加载 + BM25 索引构建 + Query 改写），稳态 **5~7s/问**（Query 改写 + Rerank 两次 LLM 调用是延迟大头；低延迟场景可关 `rag.retrieval.query-rewrite.enabled` 或换更小模型）。

**⑦ RBAC 与全局异常**（接口扫描 23/23 通过）：bob 对 alice 的知识库做检索/提问/文档/日志访问全部 **403**（`requireAccess` 在检索之前拦截，未进入检索阶段）；存储层 pgvector SQL 强制 `kb_id` 过滤（alice 检索结果 docId 全部属于她自己的库）；无/非法 token → 401、参数校验 → 400、不存在资源 → 404，全部统一 `Result` 结构；完整 Postman 集合见 [docs/postman/enterprise-rag.postman_collection.json](docs/postman/enterprise-rag.postman_collection.json)。

**⑧ RAGFlow 三件套验证**（2026-09-18）：① 标题感知分块把 4 份语料从 8 块切到 **41 块**（每"条"独立成块），search 响应带章节路径（如"第三章 考勤与休假 > 第五条 …"）与 BM25 命中词；② chunk_summary 摘要表每父块一行（12/12 章节路径覆盖）；③ 新链路回归评测 **Hit@5 保持 100%**，平均相似度 0.654 → **0.712**（更细分块定位更准）。期间模型 API 6 次瞬时超时均被 LangChain4j 重试恢复，未影响任何请求。

**⑨ 并发压测**（2026-09-20，Windows 本机应用 + VM 双库）：

| 场景 | 并发 | QPS | 平均延迟 | P95 | P99 | 失败数 |
|---|---|---|---|---|---|---|
| 接口层 `GET /auth/me`（JWT，无模型调用） | 20 | **3225** | 6ms | 16ms | 16ms | 0 |
| RAG 全链路 `POST /search`（Query改写+摘要树+双路召回+精排，每请求 2 次 LLM 调用） | 5 | 0.8 | 6.2s | 12.3s | 27.3s | 0 |

**结论**：服务本身（Tomcat/JWT/双数据源/pgvector）吞吐 3200+ QPS 无瓶颈；RAG 链路吞吐与延迟的瓶颈**完全在外部模型 API 排队**（并发 5 时 P99 已涨至 27s）。优化杠杆：延迟敏感场景关 Query 改写省一次 LLM 调用（`rag.retrieval.query-rewrite.enabled: false`）、改写/精排换更小模型、高频问题加检索缓存、生产环境消息队列削峰。

**⑩ 检索对比实验**（2026-09-20，同一评测集 30 题，改单一配置变量各跑一遍）：

| 配置 | Hit@5 | 平均相似度 | 结论 |
|---|---|---|---|
| 全链路（基线） | 100% | 0.712 | — |
| 纯向量（关 BM25） | 90% | 0.709 | **混合检索比纯向量 +10 个百分点**：3 题仅靠关键词召回 |
| 纯 BM25（关向量） | 100% | 0.000 | 30 题全部带强关键词，BM25 全中；注意向量关闭时兜底阈值失效（ask 会全量兜底，设计假设向量始终开启） |
| 关精排（Rerank off） | 100% | 0.712 | 41 块的小库精排无感知——精排的价值在 Top10 噪声多的大库场景，属低成本保险 |
| 关 Query 改写 | 100% | 0.684 | 命中率无影响，相似度略降（改写后的查询与原文更贴近） |

**⑪ 多轮对话**：`ask` 携带 `conversationId` 时注入最近 3 轮问答历史；纯指代追问实测——"它需要什么证明？"带历史正确回答（病假证明），无历史正确兜底（拒绝瞎猜）。

**⑫ 真实文档评测**（2026-09-20，见 [docs/eval/real_eval_report.md](docs/eval/real_eval_report.md)）：真实官方 PDF（专升本考试大纲，24 块）+ Redis 八股 docx（64 块），28 条标注 **Hit@5 = 100%**，平均相似度 0.710——仿真语料上训练的链路在真实文档上同样有效。附带两个真实发现：细粒度分块下"一条知识横跨多块"的标注方法论、长文档摘要串行生成的性能瓶颈（已列入优化方向）。

## 项目亮点

- **检索链路完整且每步可降级**：多查询改写 → 混合检索（向量+BM25，RRF 融合）→ Rerank 精排 → 父块展开——任一外部依赖故障自动降级，主链路不 500
- **幻觉三重防线**：检索质量阈值兜底（不调 LLM 直接拒绝）→ Prompt 强约束 → 低温度生成，库外问题实测 100% 拒绝编造
- **small-to-big 两级分块**：500 字子块保证检索定位精度，2000 字父块保证喂给 LLM 的上下文完整，同父块去重
- **对标 RAGFlow 的三件套**：① 标题感知分块 + 章节级溯源（"员工手册 > 第三章 考勤与休假 > 第五条"）；② BM25 命中词返回（可解释"为什么召回这块"）；③ RAPTOR 简化版摘要树（先搜摘要定范围再精检子块，两级检索）
- **RBAC 检索阶段过滤**：权限校验在检索之前拦截 + pgvector SQL 层 `kb_id` 兜底，双保险，越权请求进不了检索阶段
- **双数据源工程实践**：MySQL 业务 + PG 向量无分布式事务，用文档状态机 + 失败补偿保证最终一致（三个真实踩坑已文档化）
- **模型供应商可插拔**：OpenAI 兼容协议，硅基流动/百炼换 base-url + model 即切；Rerank 双协议兼容
- **数据说话**：20 单测 / 接口扫描 23/23 / 冒烟 8/8 / 检索评测 Hit@5=100% / 对比实验（混合比纯向量 +10 个百分点）/ 并发压测（服务层 3225 QPS），评测体系可复现（docs/eval/）
- **多轮对话**：会话级历史上下文，纯指代追问可答、越权会话 404、审计日志按会话归组
- **可观测性与 CI**：Actuator 健康检查/指标端点 + GitHub Actions 自动跑单测
- **手写核心不做黑盒**：BM25 倒排索引、pgvector SQL、语义分块全部手写，面试能讲清每一行原理

## 快速开始

> ✅ 2026-09-07 全链路冒烟 8/8 通过（注册/登录/建库/上传 docx/问答溯源/幻觉兜底/仅检索/审计日志），部署形态为方式二（VM 内存储 + 本机应用）；六模块实测记录（分块参数/向量校验/混合检索/问答链路/幻觉兜底/性能）见「实测验证记录」。

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
| POST | /api/kb/{kbId}/ask | 提问 → 回答 + 引用来源 + 兜底标记；body 传 `conversationId` 启用多轮对话（带最近 3 轮历史上下文，响应返回会话 id） |
| POST | /api/kb/{kbId}/ask/stream | 流式提问（SSE 逐 token 返回） |
| POST | /api/kb/{kbId}/search | 仅检索不生成（调试/评测用） |
| GET | /api/kb/{kbId}/qa-logs | 问答日志分页（审计） |
| GET | /api/admin/users | 用户列表（仅 ADMIN，RBAC 验证） |

> 启动后可打开 Swagger UI 在线调试全部接口：`http://localhost:8080/swagger-ui.html`（右上角 Authorize 填 JWT）

## 技术选型对比（面试素材）

> 五道必考题的口述版答案（分块权衡 / pgvector 选型 / 混合检索 / 幻觉抑制 / RAG vs 微调）见 **[docs/interview-notes.md](docs/interview-notes.md)**

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
4. **摘要批量生成/并行化**：当前每父块一次串行 LLM 调用，标题多的长文档（如习题集）处理数小时——批量摘要（一次调用输出多条）或并行 + 超时快速降级可解决（真实文档评测中暴露）
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
