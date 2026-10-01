# 统一问答 + 后端知识库路由 设计文档

日期：2026-10-01
状态：设计已获用户确认（"你看着改，能满足我的需求就行"），实施细节由 Claude 决定

## 1. 背景

现状：问答按知识库强隔离——接口路径带 kbId（`POST /api/kb/{kbId}/ask[/stream]`），
`conversation.kb_id`、`qa_log.kb_id` 均 NOT NULL，pgvector 所有 SQL 带 `WHERE kb_id = :kbId`，
前端 `store.chats` 按 kbId 分桶。用户必须先选库再提问，且聊天按库割裂。

用户诉求（原话）："我需要的不是这样每个知识库分开，然后聊天也分开的这种。需要聊天并在一起，
一个窗口就行，然后提问是不是后端代码能自己判断去哪个知识库里检索的。"

附带实测问题：流式 LLM 调用撞上外部 API `SocketException: Connection reset` 时直接以 error 事件
结束（非流式路径有 langchain4j RetryUtils 重试，流式路径没有）。日志（2026-10-01）显示
Connection reset 当天已出现 10 次，属外部链路不稳定。

## 2. 目标与非目标

目标：
1. 单一聊天窗口：提问不选库，后端自动决定检索范围
2. 路由粒度：LLM 选最相关的前 2-3 个库，做一次融合检索；路由失败降级搜全部库
3. 回答来源标注所属知识库名
4. 流式 LLM 调用在"尚未输出任何 token"时自动重试 1 次

非目标（明确不做）：
- 不加"手动指定知识库"前端入口
- 不动分块/检索/融合/阈值/精排参数与算法
- 不删除、不修改旧接口（eval 脚本、评测基准、README 依赖它们）
- 不改文档上传/分类/资料管理流程

## 3. 目标链路

```
POST /api/ask（无 kbId）
 ⓪ 会话解析（用户级：只校验 user_id；kb_id 为 NULL）
 ① KnowledgeRouterService.route(question, history, listMine())
      LLM 从候选库（名称/描述）中选最相关的最多 routing.top-n 个
 ② RetrievalService.retrieve(kbIds, question)   ← 单次改写 / RRF / 精排
 ③→⑨ 兜底判定 / Prompt / LLM / 落库 —— 与现有链路完全一致
```

## 4. 后端改动

### 4.1 KnowledgeRouterService（新增 service）

- 签名：`List<Long> route(String question, String history, List<KnowledgeBaseVO> candidates)`
- 决策表：

| 情况 | 行为 |
|---|---|
| 候选库 0 个 | 返回空列表，QaService 抛 `BusinessException(400, "尚未创建知识库…")` |
| 候选库 1 个 | 直接返回，不调 LLM |
| `rag.routing.enabled=false` | 返回全部候选 id |
| LLM 异常 / 解析无有效 id / 输出 `0|无明显匹配` | 返回全部候选 id（宁可多搜） |
| 正常 | 按输出顺序返回有效 id，截断到 `routing.top-n` |

- Prompt 风格仿 DocumentClassifyService：纯文本逐行输出 `id|理由`，理由≤20 字；
  候选列表按创建时间倒序、上限 `routing.max-candidates`（与 classify 相同策略）；
  描述截断防 Prompt 过长。入参 history 取会话历史末尾约 400 字（帮助"那它呢"类指代问题路由）。
- 解析逻辑抽为包级静态方法 `parseRoutingOutput(String raw, Set<Long> validIds, int topN)`
  以便单测（不 mock LLM）。
- 配置：`RagProperties.Routing { enabled=true, topN=3, maxCandidates=20 }` + application.yml 的 `rag.routing`。

### 4.2 检索层支持库集合（改 RAG 检索范围，须跑 mvn test + 更新 README 链路表）

- `VectorStoreDao`：新增 `searchByKbs(Collection<Long> kbIds, vector, topK[, scopes])`，
  SQL 由 `kb_id = :kbId` 改 `kb_id IN (:kbIds)`；**仍强制带 kb 白名单过滤（不变量 #1 存储层兜底）**。
  `VectorHit` record 增加 `kbId` 字段（SQL select 列与构造处同步）。
- `SummaryDao`：新增 `searchByKbs(Collection<Long> kbIds, vector, topN)`（`kb_id IN`）。
  `SummaryHit` 不需要 kbId（Scope 是 docId+parentIndex，docId 全局唯一）。
- `Bm25IndexService`：新增 `search(Collection<Long> kbIds, query, topK)`——各库内存索引分别检索，
  合并后按分数降序截断 topK；`Bm25Hit` 增加 `kbId`（建索引时按库盖章，rebuild 逻辑不变）。
- `RetrievalService`：新增 `retrieve(List<Long> kbIds, String query)`，旧
  `retrieve(Long kbId, query)` 改为 `return retrieve(List.of(kbId), query)`（保 21 个单测与 eval 脚本不变）。
  父块展开/文件名回填逻辑不变；新增 kbName 回填（`KnowledgeBaseMapper.selectBatchIds`）。
- `RetrievedChunk` 增加 `kbId`、`kbName` 字段。

### 4.3 QaService（新增统一重载，旧方法一行不动）

- 新增 `AskResponse ask(String question, Long conversationId)` 与
  `void askStream(String question, Long conversationId, SseEmitter emitter)`。
- 新增 `resolveConversationForUser(userId, conversationId)`：只校验 `user_id`，
  新建会话时 kb_id 留 NULL。
- **旧接口 NPE 防护**：旧 `resolveConversation` 的校验条件改为
  `conv.getKbId() == null || !conv.getKbId().equals(kbId)` → 404
  （统一会话 kb_id 为 NULL，原写法会 NPE）。
- 落库：`qaLogService.save(userId, kbIds.size()==1 ? kbIds.get(0) : null, convId, ...)`。
- 限流：新接口与 `/ask`、`/ask/stream` 共用同一配额（`checkAsk`）。
- **流式重试**：仅当 `!gotToken` 时重试 1 次（AtomicBoolean 记录是否已发过 message 事件）；
  已输出 token 后失败照旧发 `error` 事件，避免重复内容。
- 顺带：客户端主动断开产生的 `AsyncRequestNotUsableException` 在 GlobalExceptionHandler
  不再记 ERROR（降为 DEBUG），消除"停止生成"时的日志噪声。

### 4.4 AskController（新增 controller）

- `POST /api/ask` → `Result<AskResponse>`；`POST /api/ask/stream` → SseEmitter(120s)。
- 事件序与错误通道规则同旧接口（不变量 #7）：meta → message → sources / error。
- QaController 与旧接口完全不动。

### 4.5 来源 VO

- `SourceVO` 增加 `kbName`；ask 与 askStream 的来源组装处同步填充（含 SSE sources JSON）。

## 5. 数据模型（兼容性 ALTER，需在 VM MySQL 执行）

```sql
ALTER TABLE conversation MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（统一问答会话为 NULL）';
ALTER TABLE qa_log       MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（跨库问答为 NULL）';
```

- `sql/mysql_schema.sql`：建表定义改 NULL + 注释，底部"已建库升级"区追加上述两条。
- 语义：统一会话 kb_id=NULL；qa_log 单库问记录路由到的库 id，跨库记 NULL。
- 实体类 `Conversation.kbId`/`QaLog.kbId` 保持 Long（无需改代码），仅去掉 DB 的 NOT NULL。

## 6. 前端改动

- `store.js`：`chats[kbId]` 分桶 → 单一 `chat: { conversationId, messages, streaming }`；
  `currentKbId` 保留（仅供资料管理页）；`reset()` 同步调整。
- `api.js`：`askStream(question, conversationId, handlers, signal)` → `POST /api/ask/stream`。
- `chat-panel.js`：去掉 `v-if="store.currentKbId"` 门禁，始终渲染；改调新接口；
  来源折叠每条显示 `kbName`；空态文案改为"直接提问，系统自动判断检索哪些知识库"。
- `kb-panel.js` / `app.js`：不再 `ensureChat`；点击库仅切 `currentKbId`（对资料页生效）。
- `doc-panel.js`：不动。

## 7. 测试与验收

- `mvn test`：现有 21 个必须全绿（旧签名委托保证）。
- 新增单测：`parseRoutingOutput`（有效 id / 顺序 / 超 topN 截断 / 非法 id 剔除 / `0|无明显匹配` / 空输出）；
  `route()` 短路分支（0 库 / 1 库 / enabled=false，不触发 LLM）。
- 手工端到端清单：
  1. admin 登录，聊天窗口直接问"LangChain4j 1.x 里 ChatLanguageModel 改名叫什么"→ 路由到 15 号库答对，来源显示库名
  2. 问"相似度兜底阈值是多少"→ 路由到 13 号库
  3. 跨库问题（同时涉及 RAG 与 LLM）→ 命中 13/14 两库
  4. 无关问题（"今天天气"）→ 兜底话术，不是 500
  5. 同一窗口连续追问 → 复用同一 conversationId（meta 事件），`conversation.kb_id IS NULL`
  6. 旧接口回归：`/api/kb/1/ask` 正常；统一会话（kb_id=NULL）传给旧接口返回 404 而不是 NPE；
     旧会话（kb 绑定）在统一接口可继续使用（同用户即可，属有意取舍）
  7. 流式重试：日志观察真实 Connection reset 时自动重试一次（不再直接失败）
- 文档同步：README 接口表（+/api/ask、/api/ask/stream）+ RAG 链路表（+路由步骤）；
  CLAUDE.md/AGENTS.md：链路图、不变量 #1/#10 补充新语义、前端小节更新 store.chat。

## 8. 风险与取舍

- HNSW + `kb_id IN (...)` 为 post-filter，理论略降召回；当前库小向量少，无感。
- BM25 跨库合并按分数（各库 IDF 不同，分数不完全可比）——但进入 RRF 只按名次，影响可忽略。
- 路由多一次 LLM 调用（约 2~25s，外部 API 现状）；单库跳过；失败降级不阻塞。
- 旧接口与统一会话互不可见（各自校验不通过返 404），为有意取舍，前端不会跨用。
