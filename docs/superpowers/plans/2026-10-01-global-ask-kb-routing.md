# 统一问答 + 后端知识库路由 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `POST /api/ask[/stream]` 统一问答接口——提问不选知识库，后端 LLM 自动路由到最相关的前 N 个库做一次融合检索；前端合并为单一聊天窗口；流式 LLM 调用在未输出任何 token 时自动重试 1 次。

**Architecture:** 复用现有检索链路，只把"检索范围"从单个 kbId 扩成 kbId 集合：DAO 层 SQL 由 `kb_id = :kbId` 改 `kb_id IN (:kbIds)`（白名单来自 `listMine()`，存储层兜底不变）；新增 `KnowledgeRouterService` 用 LLM 从候选库中选库（失败/无匹配降级全库）；`QaService` 新增统一重载，把 ask/askStream 的公共尾段抽成私有方法供新旧接口共用；旧接口 `/api/kb/{kbId}/ask[/stream]` 与 `/search` 完全保留。

**Tech Stack:** Spring Boot 3.3 / LangChain4j 1.7（`ChatModel.chat(ChatRequest)`）/ MyBatis-Plus + NamedParameterJdbcTemplate(pgvector) / Vue3 无构建前端（static/js）

**Spec:** `docs/superpowers/specs/2026-10-01-global-ask-kb-routing-design.md`

## Global Constraints

- **不自动 git commit / git push**（用户规矩）：计划中每个任务**没有 commit 步骤**，只做编译 / 测试 / 手工验证。
- **注释保护（用户原话）**："我的注释你别改 除非就是 某行代码你修改了 那对应的注释就可以被修改掉"。既有注释原样保留（含措辞、风格）；仅当对应代码行被修改、原注释因此失真时才同步更新该条注释，且尽量少改、保持原句式；新增代码可写新注释；移动代码时注释随代码原样搬走。
- **只改该改的**：不动分块/融合/阈值/精排算法与参数；不动 `QaController`、`/api/kb/**` 旧接口行为、`doc-panel.js`、上传/分类流程；不删除任何文件。
- **改检索逻辑必须跑 `mvn test`**（现有 21 个单测必须全绿）并同步更新 README 链路表（Task 9）。
- **知识库隔离不变量**：统一问答的库白名单只能来自 `KnowledgeBaseService.listMine()`（服务端解析，客户端无法注入 kbId）；pgvector SQL 仍必须带 `kb_id` 过滤。
- **外部依赖必须降级**（不变量 6）：路由 LLM / 解析失败 → 检索全部候选库；流式重试只在未输出 token 时发生。
- 编译用 `mvn -q compile`（本机 PATH 的 java 是 1.8，**不要**用 `java` 命令验证；Maven 已绑定 JDK17）。
- 数据库在 VM `192.168.88.130`（MySQL 3306 / PG 5432，库名均为 `enterprise_rag`，密码 `MYSQL_PWD=123456`）。DDL 改动属于 spec §5 已批准范围。
- 运行中的应用：9090 端口（本会话后台任务）。改完 Java 代码需重启后才生效；改前端 js/css 刷新浏览器即生效（`addResources=true`）。
- 中文回复；代码/命令/变量名/文件路径保持英文。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `sql/mysql_schema.sql` | 改 | conversation/qa_log 的 kb_id 改可空 + 升级区注释 |
| `dao/pg/VectorHit.java` | 改 | record 增加 kbId（保留 4/5 参兼容构造器） |
| `dao/pg/Bm25Hit.java` | 改 | record 增加 kbId（保留 4/5 参兼容构造器） |
| `dao/pg/VectorStoreDao.java` | 改 | searchByKb → searchByKbs（IN 查询，select kb_id） |
| `dao/pg/SummaryDao.java` | 改 | searchByKb → searchByKbs |
| `service/Bm25IndexService.java` | 改 | search(Collection<Long>) 跨库合并；searchOne 保留原打分逻辑 |
| `service/RetrievedChunk.java` | 改 | 增加 kbId（final）、kbName |
| `service/RetrievalService.java` | 改 | retrieve(List<Long>,…) 多库版 + 旧签名委托 + kbName 回填 |
| `entity/vo/SourceVO.java` | 改 | 增加 kbName |
| `config/RagProperties.java` | 改 | 新增内部类 Routing |
| `resources/application.yml` | 改 | 新增 rag.routing 三项 |
| `service/KnowledgeRouterService.java` | **新增** | LLM 路由选库 + 降级 |
| `service/QaService.java` | 改 | 统一重载 + doAsk/doAskStream 抽公共尾段 + 流式重试 + NPE 防护 |
| `common/GlobalExceptionHandler.java` | 改 | AsyncRequestNotUsableException 降噪 |
| `controller/AskController.java` | **新增** | /api/ask + /api/ask/stream |
| `static/js/store.js` | 改 | chats[kbId] → 单一 chat |
| `static/js/api.js` | 改 | askStream 新签名 → /api/ask/stream |
| `static/js/components/chat-panel.js` | 改 | 去选库门禁、来源显示 kbName |
| `static/js/components/kb-panel.js` | 改 | 去 ensureChat |
| `static/js/app.js` | 改 | 去 ensureChat |
| `test/.../RetrievalServiceTest.java` | 改 | stub 名改复数 + 构造点加 mock + 新增 kbName 用例 |
| `test/.../Bm25IndexServiceTest.java` | 改 | search 调用加 List.of(1L) |
| `test/.../KnowledgeRouterServiceTest.java` | **新增** | 解析单测 + 短路/降级 |
| `README.md` / `CLAUDE.md` / `AGENTS.md` | 改 | 接口表 / 链路表 / 不变量 / 前端小节 |

## Review Focus

以下是 spec 隐含、但没有自动测试兜住的输入类别，按"最可能坑到人"排序；每行已挂到负责它的任务：

1. **一个知识库都没有的用户提问**（`/api/ask`）：期望 400 友好提示（"尚未创建知识库…"），不是 500/NPE → Task 4 单测覆盖 route 空候选返回空列表；Task 6 curl 用新注册用户实测 400。
2. **统一会话（kb_id=NULL）传给旧接口** `/api/kb/{kbId}/ask[/stream]`：期望 404，不是 NPE 500 → Task 5 加 `conv.getKbId() == null` 守卫；Task 6 curl 用统一接口建出的会话 id 实测 404。
3. **路由 LLM 输出格式错乱**（全角竖线、编号、幻觉 id、多行 0、空输出）：期望剔除非法项或降级全库，不崩溃、不少搜 → Task 4 parseRoutingOutput 单测 + LLM 抛异常降级用例。
4. **流式失败重试造成重复内容**：已输出 token 后连接重置，期望照旧发 error 事件、不重发（否则前端重复 token）→ Task 5 代码评审点（无自动化测试基建，Task 8 靠真实日志观察）。
5. **来源回填知识库名时数据缺失**（chunk 的 kbId 为 null / 库被并发删除）：期望 kbName 为空串、不 NPE、来源照常展示 → Task 2 `kbNameMap.getOrDefault(kbId, "")` + 空集合守卫，Task 2 新增单测断言 kbName 回填与空值。

---

### Task 1: DDL —— conversation/qa_log.kb_id 允许为空

**Files:**
- Modify: `sql/mysql_schema.sql:60,81,88-89`

**Interfaces:**
- Consumes: 无
- Produces: MySQL 两表 kb_id 可空（Task 5 的 `conv.setKbId(null)` / `qaLogService.save(..., null, ...)` 依赖）

- [ ] **Step 1: 修改 schema 文件的建表定义**

`sql/mysql_schema.sql` 第 60 行（qa_log 表）：

```sql
    kb_id             BIGINT UNSIGNED NOT NULL COMMENT '知识库 id',
```
改为：

```sql
    kb_id             BIGINT UNSIGNED          DEFAULT NULL COMMENT '知识库 id（跨库问答为 NULL）',
```

第 81 行（conversation 表）：

```sql
    kb_id      BIGINT UNSIGNED NOT NULL COMMENT '知识库 id',
```
改为：

```sql
    kb_id      BIGINT UNSIGNED          DEFAULT NULL COMMENT '知识库 id（统一问答会话为 NULL）',
```

- [ ] **Step 2: 在"已建库升级"区追加 ALTER 说明**

第 88-89 行现为：

```sql
-- 已建库升级：
-- ALTER TABLE qa_log ADD COLUMN conversation_id BIGINT UNSIGNED DEFAULT NULL COMMENT '所属会话 id';
```
在其后追加两行：

```sql
-- 统一问答（/api/ask）：会话与问答日志的 kb_id 允许为空（单库问仍记录子库 id，跨库记 NULL）
-- ALTER TABLE conversation MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（统一问答会话为 NULL）';
-- ALTER TABLE qa_log       MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（跨库问答为 NULL）';
```

- [ ] **Step 3: 在 VM MySQL 执行 ALTER**

```bash
MYSQL_PWD=123456 mysql -h 192.168.88.130 -P 3306 -u root enterprise_rag -e "
ALTER TABLE conversation MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（统一问答会话为 NULL）';
ALTER TABLE qa_log       MODIFY COLUMN kb_id BIGINT UNSIGNED NULL COMMENT '知识库 id（跨库问答为 NULL）';
"
```

- [ ] **Step 4: 验证列可空**

```bash
MYSQL_PWD=123456 mysql -h 192.168.88.130 -P 3306 -u root enterprise_rag -e "
SHOW COLUMNS FROM conversation LIKE 'kb_id';
SHOW COLUMNS FROM qa_log LIKE 'kb_id';
"
```
Expected: 两行输出的 `Null` 列均为 `YES`。

---

### Task 2: 检索层多库化（DAO + hits + RetrievalService + SourceVO.kbName）

**Files:**
- Modify: `src/main/java/com/enterprise/rag/dao/pg/VectorHit.java`
- Modify: `src/main/java/com/enterprise/rag/dao/pg/Bm25Hit.java`
- Modify: `src/main/java/com/enterprise/rag/dao/pg/VectorStoreDao.java:52-94,120-128`
- Modify: `src/main/java/com/enterprise/rag/dao/pg/SummaryDao.java:39-57`
- Modify: `src/main/java/com/enterprise/rag/service/Bm25IndexService.java:57-180`
- Modify: `src/main/java/com/enterprise/rag/service/RetrievedChunk.java`
- Modify: `src/main/java/com/enterprise/rag/service/RetrievalService.java:35-58,121-136,193-199`
- Modify: `src/main/java/com/enterprise/rag/entity/vo/SourceVO.java`
- Modify: `src/main/java/com/enterprise/rag/service/QaService.java:294-297`（仅 toSource 一行构造参数）
- Test: `src/test/java/com/enterprise/rag/service/RetrievalServiceTest.java`、`src/test/java/com/enterprise/rag/service/Bm25IndexServiceTest.java`

**Interfaces:**
- Consumes: 无
- Produces（后续任务依赖的精确签名）：
  - `VectorStoreDao.searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK)` / `searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK, List<Scope> scopes)`
  - `SummaryDao.searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK)`
  - `Bm25IndexService.search(Collection<Long> kbIds, String query, int topK)`
  - `RetrievalService.retrieve(List<Long> kbIds, String query)`（旧 `retrieve(Long, String)` 保留为委托）
  - `VectorHit(Long kbId, Long docId, Integer chunkIndex, String content, Double similarity, String parentContent, String headingPath)`（4/5 参便捷构造器保留，kbId 补 null）
  - `Bm25Hit(Long kbId, Long docId, Integer chunkIndex, String content, Double score, String parentContent, String headingPath, List<String> matchedTerms)`（4/5 参便捷构造器保留）
  - `RetrievedChunk(Long kbId, Long docId, Integer chunkIndex, String content)`，`getKbId()/getKbName()/setKbName()`
  - `SourceVO` 增加第 3 个字段 `kbName`（`@AllArgsConstructor` 顺序变为 docId, fileName, kbName, chunkIndex, content, score, headingPath, matchedTerms）

- [ ] **Step 1: VectorHit 加 kbId（保留兼容构造器）**

`dao/pg/VectorHit.java` 全文替换为：

```java
package com.enterprise.rag.dao.pg;

/** 向量召回命中，similarity = 1 - 余弦距离（越大越相似） */
public record VectorHit(Long kbId, Long docId, Integer chunkIndex, String content, Double similarity,
                        String parentContent, String headingPath) {

    public VectorHit(Long docId, Integer chunkIndex, String content, Double similarity) {
        this(null, docId, chunkIndex, content, similarity, null, null);
    }

    public VectorHit(Long docId, Integer chunkIndex, String content, Double similarity, String parentContent) {
        this(null, docId, chunkIndex, content, similarity, parentContent, null);
    }
}
```

- [ ] **Step 2: Bm25Hit 加 kbId（保留兼容构造器）**

`dao/pg/Bm25Hit.java` 全文替换为：

```java
package com.enterprise.rag.dao.pg;

import java.util.List;

/** BM25 关键词召回命中，score 为 BM25 得分，matchedTerms 为该块命中的查询词（高亮/可解释性） */
public record Bm25Hit(Long kbId, Long docId, Integer chunkIndex, String content, Double score,
                      String parentContent, String headingPath, List<String> matchedTerms) {

    public Bm25Hit(Long docId, Integer chunkIndex, String content, Double score) {
        this(null, docId, chunkIndex, content, score, null, null, List.of());
    }

    public Bm25Hit(Long docId, Integer chunkIndex, String content, Double score, String parentContent) {
        this(null, docId, chunkIndex, content, score, parentContent, null, List.of());
    }
}
```

- [ ] **Step 3: VectorStoreDao —— 单库查询改多库（IN）**

两个查询方法的 SQL 各自把 `c.kb_id = :kbId` 改为 `c.kb_id IN (:kbIds)`、select 列加 `c.kb_id`、参数名改 `kbIds`、方法名改 `searchByKbs`、形参改 `Collection<Long> kbIds`；`rowMapper()` 增加 `rs.getLong("kb_id")` 作第一个构造参数。替换 52-94 行为：

```java
    /**
     * 向量相似度检索：<=> 是 pgvector 的余弦距离运算符，
     * 1 - 距离 = 余弦相似度，越大越相关，按距离升序取 TopK。
     * kbIds 为服务端解析的可见库白名单（统一问答跨库检索）
     */
    public List<VectorHit> searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK) {
        String sql = """
                SELECT c.kb_id, c.doc_id, c.chunk_index, c.content, c.parent_content, c.heading_path,
                       1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM document_chunk c
                WHERE c.kb_id IN (:kbIds)
                ORDER BY c.embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbIds", kbIds)
                .addValue("embedding", toVectorLiteral(queryVector))
                .addValue("topK", topK);
        return jdbc.query(sql, params, rowMapper());
    }

    /**
     * 范围内向量检索（摘要树检索第二阶段）：只在摘要召回命中的 (doc_id, parent_index)
     * 范围内检索子块；scopes 为空时调用方应改用全量 searchByKbs
     */
    public List<VectorHit> searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK, List<Scope> scopes) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.kb_id, c.doc_id, c.chunk_index, c.content, c.parent_content, c.heading_path,
                       1 - (c.embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM document_chunk c
                WHERE c.kb_id IN (:kbIds)
                  AND (c.doc_id, c.parent_index) IN (
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbIds", kbIds)
                .addValue("embedding", toVectorLiteral(queryVector))
                .addValue("topK", topK);
        for (int i = 0; i < scopes.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:doc").append(i).append(", :parent").append(i).append(')');
            params.addValue("doc" + i, scopes.get(i).docId());
            params.addValue("parent" + i, scopes.get(i).parentIndex());
        }
        sql.append(")\n ORDER BY c.embedding <=> CAST(:embedding AS vector)\n LIMIT :topK");
        return jdbc.query(sql.toString(), params, rowMapper());
    }
```

`rowMapper()`（120-128 行）改为：

```java
    private org.springframework.jdbc.core.RowMapper<VectorHit> rowMapper() {
        return (rs, n) -> new VectorHit(
                rs.getLong("kb_id"),
                rs.getLong("doc_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getDouble("similarity"),
                rs.getString("parent_content"),
                rs.getString("heading_path"));
    }
```

并在 import 区加 `java.util.Collection`。**`loadChunksByKb(Long)` 不动**（BM25 仍按库建索引）。

- [ ] **Step 4: SummaryDao —— 同改**

替换 `searchByKb`（39-57 行）为：

```java
    /** 摘要召回：查询向量 vs 各父块摘要，TopK 返回 (docId, parentIndex) 范围；kbIds 为可见库白名单 */
    public List<SummaryHit> searchByKbs(Collection<Long> kbIds, float[] queryVector, int topK) {
        String sql = """
                SELECT doc_id, parent_index, summary,
                       1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                FROM chunk_summary
                WHERE kb_id IN (:kbIds)
                ORDER BY embedding <=> CAST(:embedding AS vector)
                LIMIT :topK
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("kbIds", kbIds)
                .addValue("embedding", toVectorLiteral(queryVector))
                .addValue("topK", topK);
        return jdbc.query(sql, params, (rs, n) -> new SummaryHit(
                rs.getLong("doc_id"),
                rs.getInt("parent_index"),
                rs.getString("summary"),
                rs.getDouble("similarity")));
    }
```
import 区加 `java.util.Collection`。

- [ ] **Step 5: Bm25IndexService —— 跨库检索 + 命中带 kbId**

替换 57-180 行的 `search` 方法为下面两个方法（原方法体内的**全部注释原样保留**，随代码搬进 `searchOne`）：

```java
    /** BM25 跨库检索：各库内存索引分别打分，合并后按分数降序截断 topK（进入 RRF 只按名次，跨库分数不完全可比可接受） */
    public synchronized List<Bm25Hit> search(Collection<Long> kbIds, String query, int topK) {
        List<Bm25Hit> merged = new ArrayList<>();
        for (Long kbId : kbIds) {
            merged.addAll(searchOne(kbId, query, topK));
        }
        merged.sort(Comparator.comparingDouble(Bm25Hit::score).reversed());
        return merged.size() <= topK ? merged : List.copyOf(merged.subList(0, topK));
    }

    /** 单库 BM25 检索：返回 TopK 命中（score 越大越相关），并记录每块的命中词（高亮/可解释性） */
    private List<Bm25Hit> searchOne(Long kbId, String query, int topK) {
        // 拿索引
        KbIndex index = indexes.computeIfAbsent(kbId, this::build);
        // ……以下为原 search 方法体（第 82 行到第 179 行）逐字保留，仅第 169-170 行的构造改为：
        //     pq.offer(new Bm25Hit(kbId, ref.docId(), ref.chunkIndex(), ref.content(), scores[i],
        //             ref.parentContent(), ref.headingPath(), matchedTerms.getOrDefault(i, List.of())));
    }
```

要点：① 原方法上的 `/** 拿索引 → 分词 → ... */` 等注释块随代码进入 `searchOne`；② 原 `public synchronized List<Bm25Hit> search(Long kbId, …)` 的 `synchronized` 由新的 public `search` 承担；③ import 区加 `java.util.Collection`。

- [ ] **Step 6: RetrievedChunk 加 kbId/kbName**

`service/RetrievedChunk.java`：在 `docId` 前加字段并改构造器，其余不动：

```java
    /** 所属知识库 id（溯源展示用，可空） */
    private final Long kbId;
    private final Long docId;
    private final Integer chunkIndex;
    private String content;
    private String fileName;
    /** 所属知识库名称（来源展示《库名》用） */
    private String kbName;
```
构造器改为：

```java
    public RetrievedChunk(Long kbId, Long docId, Integer chunkIndex, String content) {
        this.kbId = kbId;
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
    }
```

- [ ] **Step 7: RetrievalService —— 多库版 + 旧签名委托 + kbName 回填**

① 构造器字段末尾（`summaryDao` 之后）加：

```java
    private final KnowledgeBaseMapper knowledgeBaseMapper;
```
import 区加 `com.enterprise.rag.dao.mapper.KnowledgeBaseMapper`、`com.enterprise.rag.entity.KnowledgeBase`、`java.util.Objects`、`java.util.Set`、`java.util.Collection`（按需）。

② 57 行的方法改为"委托 + 多库主体"：在大注释块（44-56 行）**之前**插入委托方法，大注释块原样保留在多库方法上方：

```java
    /** 单库检索入口：委托多库版本（旧接口 / 评测脚本沿用） */
    public RetrievalResult retrieve(Long kbId, String query) {
        return retrieve(List.of(kbId), query);
    }

    /**
     * query ─┬─ ⓪ 改写 → [q1, q2, q3]  （1 个问题变 3 个）
     ...（44-56 行原有 ASCII 注释整段不动，末尾补一行）
     *        ⑥ 返回（多库检索：一次改写 / 一次 RRF / 一次精排，跨库统一融合）
     */
    public RetrievalResult retrieve(List<Long> kbIds, String query) {
```

③ 方法体内仅 4 处调用 + 2 处构造改名（其余注释与逻辑不动）：

| 原行 | 改为 |
|---|---|
| 79 `summaryDao.searchByKb(kbId, …)` | `summaryDao.searchByKbs(kbIds, queryVector, props.getSummary().getTopN())` |
| 87 `vectorStoreDao.searchByKb(kbId, …, scopes)` | `vectorStoreDao.searchByKbs(kbIds, queryVector, r.getVectorTopK(), scopes)` |
| 91 / 95 `vectorStoreDao.searchByKb(kbId, …)` | `vectorStoreDao.searchByKbs(kbIds, queryVector, r.getVectorTopK())` |
| 105 `bm25IndexService.search(kbId, q, …)` | `bm25IndexService.search(kbIds, q, r.getBm25TopK())` |
| 122 / 136 `new RetrievedChunk(h.docId(), h.chunkIndex(), h.content())` | `new RetrievedChunk(h.kbId(), h.docId(), h.chunkIndex(), h.content())` |

④ 文件末尾"回填文件名"（193-197 行）之后追加 kbName 回填：

```java
        // 回填知识库名（来源展示）
        Set<Long> kbIdSet = expanded.stream().map(RetrievedChunk::getKbId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> kbNameMap = kbIdSet.isEmpty() ? Map.of()
                : knowledgeBaseMapper.selectBatchIds(kbIdSet).stream()
                        .collect(Collectors.toMap(KnowledgeBase::getId, KnowledgeBase::getName, (a, b) -> a));
        expanded.forEach(c -> c.setKbName(kbNameMap.getOrDefault(c.getKbId(), "")));
```

- [ ] **Step 8: SourceVO 加 kbName + QaService.toSource 同步**

`entity/vo/SourceVO.java`：在 `fileName` 之后插入：

```java
    /** 所属知识库名称（统一问答来源可能跨库） */
    private String kbName;
```

`service/QaService.java:294-297` 的 `toSource` 改为（字段顺序与 `@AllArgsConstructor` 一致）：

```java
    private SourceVO toSource(RetrievedChunk c) {
        return new SourceVO(c.getDocId(), c.getFileName(), c.getKbName(), c.getChunkIndex(),
                c.getContent(), c.getScore(), c.getHeadingPath(), c.getMatchedTerms());
    }
```

- [ ] **Step 9: 更新两个测试文件（机械改名，断言不变）**

`RetrievalServiceTest.java`：
1. import 区加 `com.enterprise.rag.dao.mapper.KnowledgeBaseMapper`、`com.enterprise.rag.entity.KnowledgeBase`；
2. 加 mock 字段：`@Mock private KnowledgeBaseMapper knowledgeBaseMapper;`
3. @BeforeEach 构造改为：
```java
        service = new RetrievalService(embeddingService, vectorStoreDao, bm25IndexService,
                documentMapper, props, rerankService, queryRewriteService, summaryDao, knowledgeBaseMapper);
```
4. 全部 stub/verify 机械改名：`vectorStoreDao.searchByKb(eq(1L),` → `vectorStoreDao.searchByKbs(eq(List.of(1L)),`（6 处，含 185/186 行 verify）、`summaryDao.searchByKb(eq(1L),` → `summaryDao.searchByKbs(eq(List.of(1L)),`（174 行）、`bm25IndexService.search(eq(1L),` → `bm25IndexService.search(eq(List.of(1L)),`（77/97/122/151/179/202 行）；
5. 203 行的 7 参 Bm25Hit 构造补第一个参数：`new Bm25Hit(1L, 1L, 1, "关键词片段", 2.0, null, null, List.of("年假", "十三薪"))`。

`Bm25IndexServiceTest.java`：4 处 `service.search(1L, …)` → `service.search(List.of(1L), …)`（39-81 行内），断言不变。

- [ ] **Step 10: 新增 kbName 回填单测**

在 `RetrievalServiceTest` 末尾（`doc(...)` 辅助方法前）追加：

```java
    @Test
    @DisplayName("来源回填：知识库名按 kbId 回填，缺失时为空白不报错")
    void 来源回填知识库名() {
        when(rerankService.rerank(anyString(), any(), anyInt())).thenAnswer(inv -> {
            List<RetrievedChunk> candidates = inv.getArgument(1);
            int topK = inv.getArgument(2);
            return candidates.stream().limit(topK).toList();
        });
        when(embeddingService.embed("测试问题")).thenReturn(new float[1024]);
        when(vectorStoreDao.searchByKbs(eq(List.of(1L)), any(), eq(10))).thenReturn(List.of(
                new VectorHit(1L, 1L, 1, "A片段", 0.9, null, null),
                new VectorHit(2L, 2L, 1, "B片段", 0.8, null, null)));   // B 的库已被删除
        when(bm25IndexService.search(eq(List.of(1L)), eq("测试问题"), eq(10))).thenReturn(List.of());
        when(documentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                doc(1L, "文件A"), doc(2L, "文件B")));
        when(knowledgeBaseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(kb(1L, "员工手册")));

        RetrievalResult result = service.retrieve(1L, "测试问题");

        assertEquals("员工手册", result.chunks().get(0).getKbName());
        assertEquals("", result.chunks().get(1).getKbName());
    }

    private KnowledgeBase kb(Long id, String name) {
        KnowledgeBase k = new KnowledgeBase();
        k.setId(id);
        k.setName(name);
        return k;
    }
```

- [ ] **Step 11: 编译 + 跑全部单测**

```bash
mvn -q compile && mvn test
```
Expected: BUILD SUCCESS，21+1 个测试全绿（原有 21 个断言不变）。若失败按堆栈修 stub 名，不放宽断言。

---

### Task 3: RagProperties.Routing + application.yml

**Files:**
- Modify: `src/main/java/com/enterprise/rag/config/RagProperties.java:31-34,126-134`
- Modify: `src/main/resources/application.yml:106-110`

**Interfaces:**
- Consumes: 无
- Produces: `RagProperties.getRouting()` → `Routing { getEnabled()/getTopN()/getMaxCandidates() }`（Task 4 使用）

- [ ] **Step 1: RagProperties 加 Routing 内部类**

33 行 `classify` 字段之后加：

```java
    /** 统一问答的知识库路由（LLM 选库后跨库检索；关闭/失败降级为全部候选库） */
    private Routing routing = new Routing();
```

135 行 `Classify` 内部类之后（类尾）加：

```java
    @Data
    public static class Routing {
        /** 是否启用 LLM 路由（关闭时直接用全部候选库） */
        private Boolean enabled = true;
        /** 最多选中的知识库数 */
        private Integer topN = 3;
        /** 候选知识库上限（按创建时间倒序取前 N 个，防止 Prompt 过长） */
        private Integer maxCandidates = 20;
    }
```

- [ ] **Step 2: application.yml 加 rag.routing**

`classify:` 块（107-110 行）之后、`# RAG Prompt 模板` 之前插入：

```yaml
  # 统一问答（/api/ask）：LLM 从我的知识库里选最相关的前 N 个再融合检索；失败降级全库
  routing:
    enabled: true
    top-n: 3                # 最多选中的知识库数
    max-candidates: 20      # 候选知识库上限（按创建时间倒序，防 Prompt 过长）
```

- [ ] **Step 3: 编译**

```bash
mvn -q compile
```
Expected: BUILD SUCCESS。

---

### Task 4: KnowledgeRouterService（TDD）

**Files:**
- Create: `src/main/java/com/enterprise/rag/service/KnowledgeRouterService.java`
- Create: `src/test/java/com/enterprise/rag/service/KnowledgeRouterServiceTest.java`

**Interfaces:**
- Consumes: `RagProperties.getRouting()`（Task 3）、`KnowledgeBaseVO`（已有）
- Produces: `List<Long> KnowledgeRouterService.route(String question, String history, List<KnowledgeBaseVO> candidates)`；包级静态 `List<Long> parseRoutingOutput(String raw, Set<Long> validIds, int topN)`（Task 5 使用 route）

- [ ] **Step 1: 先写测试（覆盖短路分支 + 解析 + 降级）**

新建 `src/test/java/com/enterprise/rag/service/KnowledgeRouterServiceTest.java`：

```java
package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRouterServiceTest {

    @Mock
    private ChatModel chatModel;

    private KnowledgeRouterService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRouterService(chatModel, new RagProperties());
    }

    @Test
    @DisplayName("解析路由输出：剔除候选集外的幻觉 id、去重、兼容全角竖线、截断到 topN")
    void 解析路由输出() {
        Set<Long> valid = Set.of(1L, 2L, 3L);
        assertEquals(List.of(2L, 3L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n3|考勤制度\n2|重复", valid, 3));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n9|幻觉id", valid, 3));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n1|员工手册\n3|考勤制度", valid, 1));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2｜休假制度", valid, 3));
        assertTrue(KnowledgeRouterService.parseRoutingOutput("0|无明显匹配", valid, 3).isEmpty());
        assertTrue(KnowledgeRouterService.parseRoutingOutput("", valid, 3).isEmpty());
        assertTrue(KnowledgeRouterService.parseRoutingOutput(null, valid, 3).isEmpty());
    }

    @Test
    @DisplayName("短路：无候选库返回空列表，不调 LLM")
    void 空候选返回空() {
        assertTrue(service.route("年假怎么休", "", List.of()).isEmpty());
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("短路：唯一候选直接返回，不调 LLM")
    void 单库短路不调LLM() {
        assertEquals(List.of(7L), service.route("年假怎么休", "", List.of(kb(7L, "员工手册"))));
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("降级：路由关闭时直接用全部候选库")
    void 路由关闭用全部候选() {
        RagProperties props = new RagProperties();
        props.getRouting().setEnabled(false);
        KnowledgeRouterService disabled = new KnowledgeRouterService(chatModel, props);

        assertEquals(List.of(1L, 2L), disabled.route("年假", "", List.of(kb(1L, "A"), kb(2L, "B"))));
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("降级：LLM 调用失败返回全部候选库")
    void LLM失败降级全部候选() {
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("connection reset"));

        assertEquals(List.of(1L, 2L), service.route("年假", "", List.of(kb(1L, "A"), kb(2L, "B"))));
    }

    @Test
    @DisplayName("正常路由：按模型输出顺序返回命中的库")
    void 正常路由() {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("2|休假制度\n1|员工手册"));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        assertEquals(List.of(2L, 1L),
                service.route("年假怎么休", "问：入职流程", List.of(kb(1L, "员工手册"), kb(2L, "休假制度"))));
    }

    private KnowledgeBaseVO kb(Long id, String name) {
        return new KnowledgeBaseVO(id, name, "描述", 1L, null, null);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败（类不存在）**

```bash
mvn -q test -Dtest=KnowledgeRouterServiceTest
```
Expected: 编译错误 `cannot find symbol: class KnowledgeRouterService`。

- [ ] **Step 3: 实现 KnowledgeRouterService**

新建 `src/main/java/com/enterprise/rag/service/KnowledgeRouterService.java`：

```java
package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 统一问答的知识库路由：LLM 从当前用户可见的知识库中选最相关的前 N 个。
 * 只读库列表、不针对某个 kb 操作，因此不走 requireAccess。
 * <p>
 * 不变量 6：LLM 异常 / 解析无有效 id / 输出「无明显匹配」一律降级为全部候选库 —— 宁可多搜，不能漏搜。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeRouterService {

    private final ChatModel chatModel;
    private final RagProperties props;

    /** 注入 Prompt 的历史长度上限（帮助"那它呢"类指代问题路由） */
    private static final int HISTORY_MAX_CHARS = 400;

    private static final String ROUTE_PROMPT = """
            你是企业知识库检索路由助手。用户提问时不会指定知识库，请你判断该问题最可能涉及哪几个知识库。

            候选知识库：
            %s

            输出要求：
            1. 最多选择 %d 个最相关的知识库，每行一个，格式为：知识库id|理由，理由不超过 20 字；
            2. 按相关度从高到低排序，不要编号、不要解释、不要输出候选之外的 id；
            3. 如果问题与所有候选知识库都无关，只输出一行：0|无明显匹配。
            示例：
            2|考勤与休假制度
            1|员工手册相关条款
            """;

    private static final String QUESTION_TEMPLATE = """
            对话历史：
            %s
            用户问题：%s
            """;

    /** 路由选库：返回参与检索的库 id（顺序即相关度）；任一环节失败降级为全部候选库 */
    public List<Long> route(String question, String history, List<KnowledgeBaseVO> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 唯一候选直通：省一次 LLM 调用（结果一样，没必要花钱）
        if (candidates.size() == 1) {
            return List.of(candidates.get(0).getId());
        }
        if (!Boolean.TRUE.equals(props.getRouting().getEnabled())) {
            return allIds(candidates);
        }
        int topN = props.getRouting().getTopN();
        int maxCandidates = props.getRouting().getMaxCandidates();
        List<KnowledgeBaseVO> limited = candidates.size() > maxCandidates
                ? candidates.subList(0, maxCandidates) : candidates;   // listMine 已按创建时间倒序
        try {
            String candidateText = limited.stream()
                    .map(c -> "- id=%d 名称=%s 描述=%s".formatted(c.getId(), c.getName(),
                            StringUtils.hasText(c.getDescription()) ? c.getDescription() : "（无描述）"))
                    .collect(Collectors.joining("\n"));
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(ROUTE_PROMPT.formatted(candidateText, topN)),
                            UserMessage.from(QUESTION_TEMPLATE.formatted(truncateHistory(history), question)))
                    .build());
            Set<Long> validIds = limited.stream().map(KnowledgeBaseVO::getId).collect(Collectors.toSet());
            List<Long> picked = parseRoutingOutput(response.aiMessage().text(), validIds, topN);
            if (picked.isEmpty()) {
                log.info("路由未匹配到知识库，降级检索全部候选库");
                return allIds(candidates);
            }
            log.info("路由命中知识库 {}", picked);
            return picked;
        } catch (Exception e) {
            log.warn("知识库路由失败，降级检索全部候选库: {}", e.getMessage());
            return allIds(candidates);
        }
    }

    /**
     * 解析路由输出（沿用项目「按行纯文本」风格，不引入 JSON 解析）：
     * 每行按 | 切成 id 和理由（兼容全角竖线）；抠不出数字、id=0（无明显匹配）、
     * 不在候选集内（模型幻觉）、重复的行都剔除；最多取 topN 个
     */
    static List<Long> parseRoutingOutput(String raw, Set<Long> validIds, int topN) {
        if (raw == null) {
            return List.of();
        }
        List<Long> picked = new ArrayList<>();
        for (String line : raw.strip().split("\\R")) {
            String[] parts = line.split("[|｜]", 2);
            Long id = parseId(parts[0]);
            if (id == null || id == 0 || !validIds.contains(id) || picked.contains(id)) {
                continue;
            }
            picked.add(id);
            if (picked.size() >= topN) {
                break;
            }
        }
        return picked;
    }

    /** 从「2」「id=2」「知识库2」之类输出里抠出数字 id，抠不出或过长返回 null */
    private static Long parseId(String text) {
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /** 取历史末尾 N 字符：多轮追问的指代词在最近几轮，同时控制 Prompt 长度 */
    private static String truncateHistory(String history) {
        if (history == null || history.isBlank()) {
            return "（无）";
        }
        return history.length() <= HISTORY_MAX_CHARS ? history
                : history.substring(history.length() - HISTORY_MAX_CHARS);
    }

    private static List<Long> allIds(List<KnowledgeBaseVO> candidates) {
        return candidates.stream().map(KnowledgeBaseVO::getId).toList();
    }
}
```

- [ ] **Step 4: 跑测试确认全绿**

```bash
mvn -q test -Dtest=KnowledgeRouterServiceTest
```
Expected: 6 个测试全部 PASS。

---

### Task 5: QaService 统一问答 + 流式重试 + 旧接口 NPE 防护

**Files:**
- Modify: `src/main/java/com/enterprise/rag/service/QaService.java`（60-116 的 ask、126-153 的 resolveConversation、188-265 的 askStream）
- Modify: `src/main/java/com/enterprise/rag/common/GlobalExceptionHandler.java`

**Interfaces:**
- Consumes: `KnowledgeRouterService.route(...)`（Task 4）、`RetrievalService.retrieve(List<Long>, …)`（Task 2）、`SourceVO.kbName`（Task 2）
- Produces（Task 6 使用）：
  - `AskResponse QaService.ask(String question, Long conversationId)`
  - `void QaService.askStream(String question, Long conversationId, SseEmitter emitter)`
  - 旧签名 `ask(Long, String, Long)` / `askStream(Long, String, Long, SseEmitter)` / `search(Long, String)` 行为不变

- [ ] **Step 1: ask 拆成"旧接口头 + 新接口头 + 公共尾段"**

① 替换 `ask(Long kbId, …)`（60-116 行）为下面三个方法。原方法里 【问答-1】到【问答-5】的注释与代码**原样搬进 `doAsk`**；`kbId` 出现处按表改为 `logKbId` / `userId`：

```java
    public AskResponse ask(Long kbId, String question, Long conversationId) {
        // 记开始时间，最后算耗时
        long start = System.currentTimeMillis();
        // 知识库隔离校验
        knowledgeBaseService.requireAccess(kbId);
        // 接口限流：/ask 每请求消耗 2~3 次 LLM 调用，防 key 被刷烧钱
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);

        // 【问答-0】多轮对话：解析会话（新会话建档；已存在会话校验归属并取最近几轮历史）
        ConversationContext convCtx = resolveConversation(kbId, userId, conversationId);
        return doAsk(question, convCtx, List.of(kbId), start, userId);
    }

    /** 统一问答（跨库路由）：不指定知识库，由 KnowledgeRouterService 从当前用户可见库中选 */
    public AskResponse ask(String question, Long conversationId) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        ConversationContext convCtx = resolveConversationForUser(userId, conversationId);
        // 路由放在检索之前：候选为空会抛 400，此刻还没开始产出响应
        List<Long> kbIds = route(question, convCtx.history());
        return doAsk(question, convCtx, kbIds, start, userId);
    }

    /** 公共尾段：检索 → 兜底 → Prompt → LLM → 溯源 → 审计落库；单库问记该库 id，跨库记 NULL */
    private AskResponse doAsk(String question, ConversationContext convCtx, List<Long> kbIds,
                              long start, Long userId) {
        Long convId = convCtx.convId();
        String history = convCtx.history();
        Long logKbId = kbIds.size() == 1 ? kbIds.get(0) : null;

        // 【问答-1】混合检索（向量 + BM25，RRF 融合）
        RetrievalResult retrieval = retrievalService.retrieve(kbIds, question);

        boolean fallback;
        String answer;
        List<SourceVO> sources;
        String context;

        // 【问答-2】幻觉兜底：检索出来的资料质量不达标（无召回 / 最佳向量相似度低于阈值）
        // 直接返回固定话术、不调 LLM —— 从根上禁止模型编造
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            log.info("知识库{}检索质量不达标(空召回={}, 最佳相似度={})，触发幻觉兜底",
                    kbIds, retrieval.isEmpty(), retrieval.maxVectorSimilarity());
            fallback = true;
            answer = FALLBACK_ANSWER;
            sources = List.of();
            context = "";
        } else {
            // 【问答-3】Prompt 组装：检索片段 + 对话历史注入模板
            context = buildContext(retrieval.chunks());
            String systemPrompt = props.getPromptTemplate()
                    .replace("{context}", context)
                    .replace("{history}", history.isBlank() ? "（无）" : history)
                    .replace("{question}", question);

            // 【问答-4】LLM 调用（低温度 0.1 + Prompt 内强约束"不得编造"）
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt), UserMessage.from(question))
                    .build());
            answer = response.aiMessage().text();

            // 溯源：返回引用来源片段（含章节路径与命中词）
            sources = retrieval.chunks().stream().map(this::toSource).toList();
            // 二次兜底：模型仍输出"找不到"话术时同样标记（审计用）
            fallback = answer.contains("没有找到相关资料");
        }

        // 【问答-5】审计落库：提问/回答/检索上下文/来源/耗时全量记录（挂到会话下）
        long latency = System.currentTimeMillis() - start;
        qaLogService.save(userId, logKbId, convId, question, answer, sources, context, fallback, latency);
        return new AskResponse(answer, fallback, sources, convId);
    }
```

② 字段区（44-53 行）`retrievalService` 之后加：

```java
    private final KnowledgeRouterService knowledgeRouterService;
```
import 区加 `com.enterprise.rag.entity.vo.KnowledgeBaseVO`。

③ 在 `resolveConversation` 之后新增：

```java
    /** 统一问答的会话解析：只校验归属用户（kb_id 为 NULL，没有库维度） */
    private ConversationContext resolveConversationForUser(Long userId, Long conversationId) {
        if (conversationId != null) {
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv == null || !conv.getUserId().equals(userId)) {
                throw new BusinessException(404, "会话不存在或不属于当前用户");
            }
            return new ConversationContext(conversationId, buildHistory(conversationId));
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);   // 统一问答：不绑定知识库，kb_id 留 NULL
        conversationMapper.insert(conv);
        return new ConversationContext(conv.getId(), "");
    }

    /** 路由选库：候选来自 listMine（天然白名单，客户端无法注入 kbId）；一个库都没有时直接报错 */
    private List<Long> route(String question, String history) {
        List<KnowledgeBaseVO> candidates = knowledgeBaseService.listMine();
        if (candidates.isEmpty()) {
            throw new BusinessException(400, "尚未创建知识库，请先在「资料管理」中创建并上传文档");
        }
        return knowledgeRouterService.route(question, history, candidates);
    }
```

④ **旧 resolveConversation NPE 防护**（140-141 行）：校验条件加一条，并把其上方的条件说明注释**同步补一行**（该注释描述的就是这个 if）：

```java
            /**
             * 如果满足以下任意一种情况，就认为这个请求是不合法的
             *
             * 根据传入的 conversationId 没有查找到对应的会话记录
             * 查出来的这个会话，它的归属用户 ID 不等于 当前发起请求的用户 ID
             * 这个会话原本绑定的知识库 ID 不等于 当前请求想要查询的知识库 ID
             * 这个会话是统一问答会话（kb_id 为 NULL，没有库维度），不能用在按库接口里
             */
            if (conv == null || !conv.getUserId().equals(userId)
                    || conv.getKbId() == null || !conv.getKbId().equals(kbId)) {
                throw new BusinessException(404, "会话不存在或不属于当前知识库");
            }
```

- [ ] **Step 2: askStream 同样拆分 + 流式重试**

① 替换 `askStream(Long kbId, …)`（188-265 行）为下面 4 个成员（原匿名类内 onPartialResponse/onCompleteResponse/onError 的三段注释原样保留）：

```java
    // 和 ask 走同一条链路（检索 → 兜底 → 组装 prompt → 调 LLM → 溯源 → 审计），区别只有一个：答案边生成边推送，不等全部生成完。
    public void askStream(Long kbId, String question, Long conversationId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        knowledgeBaseService.requireAccess(kbId);
        // 限流与 userId 都必须在请求线程取：SSE 回调跑在 langchain4j 的线程池上，那里读不到 SecurityContext
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        // 会话解析同样在请求线程做（新会话要写库）。此刻 emitter 还没 initialize，
        // 抛出的 404 照走 GlobalExceptionHandler 返回 JSON —— 和 requireAccess/checkAsk 是同一条通道
        ConversationContext conv = resolveConversation(kbId, userId, conversationId);
        // 首个事件回传会话 id：首次提问时前端还不知道它，拿不到就无法追问
        sendJson(emitter, "meta", Map.of("conversationId", conv.convId()));
        doAskStream(emitter, question, conv, List.of(kbId), start, userId);
    }

    /** 统一流式问答（跨库路由）：事件序同旧接口；路由在 meta 之前完成，出错仍走 JSON 通道 */
    public void askStream(String question, Long conversationId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        ConversationContext conv = resolveConversationForUser(userId, conversationId);
        // 路由必须在第一个 SSE 事件之前：候选为空抛 400 时响应还没开始，才能返回 JSON
        List<Long> kbIds = route(question, conv.history());
        // 首个事件回传会话 id：首次提问时前端还不知道它，拿不到就无法追问
        sendJson(emitter, "meta", Map.of("conversationId", conv.convId()));
        doAskStream(emitter, question, conv, kbIds, start, userId);
    }

    /** 流式任务上下文：onError 自动重试时要原样重建一次调用，用 record 打包避免长参数列表 */
    private record StreamJob(SseEmitter emitter, String question, String systemPrompt, List<SourceVO> sources,
                             String context, Long convId, Long logKbId, long start, Long userId,
                             AtomicBoolean gotToken, AtomicBoolean retried) {
    }

    /** 公共尾段：检索 → 兜底 → Prompt → 发起流式调用（与 doAsk 同判定逻辑） */
    private void doAskStream(SseEmitter emitter, String question, ConversationContext conv,
                             List<Long> kbIds, long start, Long userId) {
        Long logKbId = kbIds.size() == 1 ? kbIds.get(0) : null;
        RetrievalResult retrieval = retrievalService.retrieve(kbIds, question);

        // 幻觉兜底：与同步接口同一判定逻辑
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            sendEvent(emitter, "message", FALLBACK_ANSWER);
            sendEvent(emitter, "sources", "[]");
            qaLogService.save(userId, logKbId, conv.convId(), question, FALLBACK_ANSWER, List.of(), "",
                    true, System.currentTimeMillis() - start);
            emitter.complete();
            return;
        }

        String context = buildContext(retrieval.chunks());
        String systemPrompt = props.getPromptTemplate()
                .replace("{context}", context)
                .replace("{history}", conv.history().isBlank() ? "（无）" : conv.history())
                .replace("{question}", question);
        List<SourceVO> sources = retrieval.chunks().stream().map(this::toSource).toList();

        streamChat(new StreamJob(emitter, question, systemPrompt, sources, context, conv.convId(), logKbId,
                start, userId, new AtomicBoolean(false), new AtomicBoolean(false)));
        // 给这个 emitter 设置一个规则：如果它超时了，就自动执行关闭操作。
        emitter.onTimeout(emitter::complete);
    }

    /** 发起一次流式 LLM 调用；未输出任何 token 就失败时自动重试一次（已出字重试会导致前端重复内容） */
    private void streamChat(StreamJob job) {
        streamingChatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(job.systemPrompt()), UserMessage.from(job.question()))
                .build(), new StreamingChatResponseHandler() {
            /**
             * 触发时机：大模型每生成一个 token（一个词或一个字），就会调用一次这个方法。
             * 效果：前端收到后立即渲染，用户看到的就是一个字一个字往外蹦的打字机效果。
             */
            @Override
            public void onPartialResponse(String partial) {
                job.gotToken().set(true);
                sendEvent(job.emitter(), "message", partial);
            }

            /**
             * 触发时机：大模型全部生成完毕。
             * 从完整响应里提取出最终的完整答案文本。注意，虽然前面已经逐字推送过了，但这里拿到的是完整的、拼接好的答案，用于落库审计。
            */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                String answer = response.aiMessage().text();
                try {
                    sendEvent(job.emitter(), "sources", objectMapper.writeValueAsString(job.sources()));
                    qaLogService.save(job.userId(), job.logKbId(), job.convId(), job.question(), answer,
                            job.sources(), job.context(), answer.contains("没有找到相关资料"),
                            System.currentTimeMillis() - job.start());
                } catch (Exception e) {
                    // 异常不能穿出回调：langchain4j 会转成 onError，而 emitter 已 complete
                    log.error("流式问答落库/溯源失败", e);
                } finally {
                    job.emitter().complete();
                }
            }

            /**
             * 触发时机：大模型调用过程中发生异常（比如 API 超时、网络中断、余额不足等）。
             * 目的：留下失败记录，方便日后统计失败率、排查问题。
             */
            @Override
            public void onError(Throwable error) {
                // 外部 API 连接重置/超时时：还没吐过任何内容才重试（重试上限 1 次），
                // 已输出 token 再重发会让前端出现重复段落
                if (!job.gotToken().get() && job.retried().compareAndSet(false, true)) {
                    log.warn("流式调用失败且尚未输出内容，自动重试一次: {}", error.getMessage());
                    streamChat(job);
                    return;
                }
                log.error("流式回答失败", error);
                sendEvent(job.emitter(), "error", "回答生成失败: " + error.getMessage());
                qaLogService.save(job.userId(), job.logKbId(), job.convId(), job.question(), "", List.of(),
                        job.context(), false, System.currentTimeMillis() - job.start());
                job.emitter().complete();
            }
        });
    }
```

② import 区加 `java.util.concurrent.atomic.AtomicBoolean`。

- [ ] **Step 3: GlobalExceptionHandler 对客户端断开降噪**

`common/GlobalExceptionHandler.java` 在 `handleNotFound` 之后加：

```java
    /** 客户端主动断开（刷新页面/停止生成）导致 SSE 写失败：连接已不可用、无响应可写，降为 DEBUG 免刷 ERROR 日志 */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开，忽略异步响应写入失败: {}", e.getMessage());
    }
```
import 区加 `org.springframework.web.context.request.async.AsyncRequestNotUsableException`。

- [ ] **Step 4: 编译 + 跑全部单测**

```bash
mvn -q compile && mvn test
```
Expected: BUILD SUCCESS，全部测试绿（QaService 无单测，靠 Task 6 冒烟与 Task 8 端到端覆盖）。

---

### Task 6: AskController + 冒烟验证

**Files:**
- Create: `src/main/java/com/enterprise/rag/controller/AskController.java`

**Interfaces:**
- Consumes: `QaService.ask(String, Long)` / `QaService.askStream(String, Long, SseEmitter)`（Task 5）
- Produces: `POST /api/ask`、`POST /api/ask/stream`（Task 7 前端调用）

- [ ] **Step 1: 新建 AskController**

```java
package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.dto.AskRequest;
import com.enterprise.rag.entity.vo.AskResponse;
import com.enterprise.rag.service.QaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一问答接口：不指定知识库，后端自动路由到最相关的库检索（当前用户可见范围内）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AskController {

    private final QaService qaService;

    /** 统一提问：返回回答 + 引用来源（含来源所属知识库名）+ 是否触发兜底 */
    @PostMapping("/ask")
    public Result<AskResponse> ask(@Valid @RequestBody AskRequest req) {
        return Result.ok(qaService.ask(req.getQuestion(), req.getConversationId()));
    }

    /**
     * 统一流式提问（SSE）：事件序与错误通道规则同 /api/kb/{kbId}/ask/stream
     * （meta 会话 id → message 逐 token → sources 溯源 JSON；同步异常返 JSON）
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AskRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时
        qaService.askStream(req.getQuestion(), req.getConversationId(), emitter);
        return emitter;
    }
}
```

- [ ] **Step 2: 编译 + 重启应用**

```bash
mvn -q compile
```
然后停掉后台应用任务并用同样方式重启：`SERVER_PORT=9090 mvn spring-boot:run`（先 TaskStop 旧任务 bpx2m3vax）。等日志出现 `Started EnterpriseRagApplication`。

> 本机控制台是 GBK，含中文的 JSON 一律先写成 UTF-8 文件再 `--data-binary @file`，不要内联 `-d '中文'`。

- [ ] **Step 3: 冒烟——旧接口回归（来源带 kbName）**

```bash
T=$(cat /tmp/admin_token.txt)
printf '{"question":"补卡次数一个月不能超过几次？"}' > /tmp/q_old.json
curl -s -X POST http://localhost:9090/api/kb/1/ask -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q_old.json -o /tmp/r_old.json
python -c "import json;d=json.load(open('/tmp/r_old.json',encoding='utf-8'))['data'];print(d['answer'][:60]);print([(s['fileName'],s['kbName']) for s in d['sources']])"
```
Expected: 答案非空；sources 每项含正确的 `kbName`。若 token 过期先重新登录写回 `/tmp/admin_token.txt`。

- [ ] **Step 4: 冒烟——统一接口（含会话复用与来源跨库）**

```bash
T=$(cat /tmp/admin_token.txt)
# 第一问（无会话）→ 拿 conversationId
printf '{"question":"LangChain4j 1.x 里 ChatLanguageModel 改名叫什么"}' > /tmp/q1.json
curl -s -X POST http://localhost:9090/api/ask -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q1.json -o /tmp/r1.json
python -c "import json;d=json.load(open('/tmp/r1.json',encoding='utf-8'))['data'];print(d['conversationId'], d['answer'][:60]);print({s['kbName'] for s in d['sources']})"

# 第二问带上会话 id → 应复用同一会话
CID=$(python -c "import json;print(json.load(open('/tmp/r1.json',encoding='utf-8'))['data']['conversationId'])")
printf '{"question":"那 EmbeddingModel 的 embed() 返回什么？","conversationId":%s}' "$CID" > /tmp/q2.json
curl -s -X POST http://localhost:9090/api/ask -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q2.json -o /tmp/r2.json
python -c "import json;d=json.load(open('/tmp/r2.json',encoding='utf-8'))['data'];print(d['conversationId'], d['answer'][:60])"
```
Expected: 第一问返回非空 conversationId、答案正确且来源 kbName 为「LangChain4j 知识」；第二问 conversationId 相同、答案正确（多轮历史生效）。

- [ ] **Step 5: 冒烟——错误通道三条**

```bash
T=$(cat /tmp/admin_token.txt)
CID=$(python -c "import json;print(json.load(open('/tmp/r1.json',encoding='utf-8'))['data']['conversationId'])")

# ① 统一会话（kb_id=NULL）传给旧接口 → 404（不是 500）
printf '{"question":"测试","conversationId":%s}' "$CID" > /tmp/q_bad.json
curl -s -X POST http://localhost:9090/api/kb/1/ask -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q_bad.json

# ② 新注册用户（无知识库）调统一接口 → 400 友好提示
printf '{"username":"smoke_nokb","password":"123456"}' > /tmp/q_reg.json
curl -s -X POST http://localhost:9090/api/auth/register -H "Content-Type: application/json" --data-binary @/tmp/q_reg.json
T2=$(curl -s -X POST http://localhost:9090/api/auth/login -H "Content-Type: application/json" --data-binary @/tmp/q_reg.json | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
printf '{"question":"你好"}' > /tmp/q_hi.json
curl -s -X POST http://localhost:9090/api/ask -H "Authorization: Bearer $T2" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q_hi.json

# ③ 流式统一接口（meta 必须是第一帧）
printf '{"question":"RRF 融合的公式是什么"}' > /tmp/q_stream.json
curl -N -s -X POST http://localhost:9090/api/ask/stream -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json; charset=utf-8" --data-binary @/tmp/q_stream.json | head -c 400
```
Expected: ① `{"code":404,...}`；② `{"code":400,"message":"尚未创建知识库…"}`（HTTP 400，非 500）；③ 首帧为 `event:meta`，随后 message 逐 token。（`smoke_nokb` 若已存在，注册报 400 可忽略，直接登录。）

- [ ] **Step 6: 数据库抽查**

```bash
MYSQL_PWD=123456 mysql -h 192.168.88.130 -P 3306 -u root enterprise_rag -e "
SELECT id, kb_id, user_id FROM conversation ORDER BY id DESC LIMIT 3;
SELECT id, kb_id, conversation_id, LEFT(question,20) q FROM qa_log ORDER BY id DESC LIMIT 5;"
```
Expected: 统一问答会话 `kb_id` 为 `NULL`；qa_log 单库问有 kb_id、跨库问为 NULL，conversation_id 非空。

---

### Task 7: 前端 —— 合并为单一聊天窗口

**Files:**
- Modify: `src/main/resources/static/js/store.js`
- Modify: `src/main/resources/static/js/api.js:150-169,266`
- Modify: `src/main/resources/static/js/components/chat-panel.js`
- Modify: `src/main/resources/static/js/components/kb-panel.js:61-64,76-77,98`
- Modify: `src/main/resources/static/js/app.js:80-88`

**Interfaces:**
- Consumes: `POST /api/ask/stream`（Task 6）
- Produces: 无（终点）

- [ ] **Step 1: store.js —— chats 分桶改单一 chat**

文件头注释（1-4 行）与其描述的代码同时改变，改为：

```js
/**
 * 全局状态（Vue.reactive 单例）。
 * chat 是唯一会话状态：统一问答不按知识库分桶，一个窗口一份历史。
 */
```

`chats: {},`（15 行）改为：

```js
    chat: { conversationId: null, messages: [], streaming: false },   // 唯一会话状态（统一问答：一个窗口）
```

删除 `ensureChat` 函数（24-30 行）。`reset()` 内 `store.chats = {};`（43 行）改为：

```js
    store.chat = { conversationId: null, messages: [], streaming: false };
```

导出行（49 行）改为：

```js
  global.RagStore = { store, currentKb, reset };
```

- [ ] **Step 2: api.js —— askStream 新签名**

`askStream(kbId, question, conversationId, handlers, signal)`（155 行）改为：

```js
  async function askStream(question, conversationId, handlers, signal) {
    const cb = handlers || {};

    const res = await fetch('/api/ask/stream', {
```

函数上方注释（152-153 行）中括号里的错误清单同步去掉 403、补 400：

```js
   * 流中的错误走 onError 回调；只有「流还没起来就失败」（未登录 401 / 会话 404 / 未创建知识库 400 / 限流 429）
   * 才 throw —— 那时后端返回的是普通 JSON，不是 SSE。
```

- [ ] **Step 3: chat-panel.js —— 去门禁 + 显示库名**

① 模板：去掉 `<template v-if="store.currentKbId">`（5 行）与结尾 `<el-empty v-else …>`（71-72 行）两层包裹，`chat-head` 到 `chat-foot` 直接作为 `.chat-panel` 的子节点（整段反缩进 2 空格，纯空白调整）。

② 头部标题（9 行）改为：

```html
            <span class="chat-head-name">统一问答</span>
```

③ 空态文案（20 行）改为：

```html
                    description="直接提问，系统会自动判断检索哪些知识库" :image-size="90" />
```

④ 来源头部（44 行后）插入库名标签：

```html
                      <span class="src-file">《{{ s.fileName }}》第 {{ s.chunkIndex }} 段</span>
                      <el-tag v-if="s.kbName" size="small" type="info" effect="plain">{{ s.kbName }}</el-tag>
```

⑤ 计算属性 `chat()`（88-90 行）改为：

```js
    chat() {
      return RagStore.store.chat;
    },
```
并删除 `kbName()` 计算属性（91-94 行，模板已不再使用）。

⑥ `newChat()`（104 行）：`const chat = RagStore.ensureChat(this.store.currentKbId);` 改为：

```js
      const chat = this.store.chat;
```

⑦ `send()` 内三处：

```js
      if (!text || chat.streaming) return;                     // 原 124 行去 !this.store.currentKbId
```
删除原 126 行 `const kbId = this.store.currentKbId;`，原 138 行改为：

```js
        await RagApi.askStream(text, chat.conversationId, {
```

⑧ 404 分支（157-162 行）注释与文案随行为更新：

```js
        if (e.code === 404) {
          // 会话失效自愈：conversationId 已不存在或不属于当前用户。
          // 不自动重发——重发会再吃一次限流配额，让用户自己决定
          chat.conversationId = null;
          msg.error = '会话已失效，已为你开启新会话，请重新提问';
```

- [ ] **Step 4: kb-panel.js —— 去 ensureChat**

`select()`（61-64 行）删除 `RagStore.ensureChat(kb.id);` 一行；`create()`（77 行）删除 `RagStore.ensureChat(kb.id);` 一行；`remove()`（98 行）删除 `delete store.chats[kb.id];` 一行。其余不动。

- [ ] **Step 5: app.js —— loadKbs 去 ensureChat**

80-88 行改为：

```js
      async loadKbs() {
        store.kbs = await RagApi.listKb();
        if (store.kbs.length && !store.currentKbId) {
          store.currentKbId = store.kbs[0].id;
        }
      },
```

- [ ] **Step 6: 浏览器走查（刷新即可，无需重启）**

打开 `http://localhost:9090/`（登录 admin）：
1. 不点任何知识库，直接在对话页提问 → 流式回答正常、来源显示库名标签；
2. 追问"那它呢/它的公式呢" → 复用同一会话（header 未变、回答正确）；
3. 点「新对话」→ 会话 id 清空，下一问开新会话；
4. 切到「资料管理」→ 上传/列表/删除仍正常（currentKbId 生效）；
5. 浏览器控制台零报错（尤其无 `chats[...]` undefined、无 ensureChat 报错）。

---

### Task 8: 端到端验收（spec §7 清单）

**Files:** 无（只验证）

- [ ] **Step 1: 七条清单逐条过**

1. 问「LangChain4j 1.x 里 ChatLanguageModel 改名叫什么」→ 路由到 LangChain4j 库、答 ChatModel、来源库名正确；
2. 问「相似度兜底阈值是多少」→ 路由到 RAG 项目库（阈值 0.4）；
3. 跨库问题（同时涉及 RAG 与 LLM 概念，如「RRF 和 Embedding 的关系」）→ 来源跨 2 个库；
4. 无关问题「今天天气怎么样」→ 兜底话术（`isFallback=true`、sources 空），不是 500；
5. 同一窗口连续追问 → `conversation.id` 复用且 `kb_id IS NULL`（SQL 查 conversation/qa_log 两表核对）；
6. 旧接口回归：`/api/kb/{kbId}/ask|search` 正常（Task 6 Step 3 已验）、统一会话传旧接口 404（Task 6 Step 5 已验）；
7. 流式重试：`grep "尚未输出内容，自动重试一次" 应用日志` —— 出现即说明真实 Connection reset 被自动兜住；若当日未复现，记录"未观察到"如实汇报，不伪造。

- [ ] **Step 2: 跑一次 mvn test 终检**

```bash
mvn test
```
Expected: 全绿（21 原有 + 1 kbName + 6 路由 = 28 个）。

---

### Task 9: 文档同步

**Files:**
- Modify: `README.md`（接口清单表 253-272 行附近；问答链路 90-100 行附近）
- Modify: `CLAUDE.md`（链路图、不变量 #1/#10、前端小节）
- Modify: `AGENTS.md`（与 CLAUDE.md 同源内容同步）

**Interfaces:** 无代码接口

- [ ] **Step 1: README 接口清单加两行**

在 `/api/kb/{kbId}/ask` 两行之前插入：

```
| POST | /api/ask | **统一提问**：不指定知识库，后端 LLM 自动路由到最相关的库（≤3 个）融合检索；失败/无匹配降级全部库；body/响应同 /api/kb/{kbId}/ask（来源含 kbName） |
| POST | /api/ask/stream | **统一流式提问**（SSE）：事件序与 /api/kb/{kbId}/ask/stream 一致；与 /ask 系列共用 10/min 限流 |
```

- [ ] **Step 2: README 问答链路补路由步骤**

「问答（在线 RAG 链路）」代码块第 ① 步之前插入：

```
  → ⓪ 知识库路由（仅 /api/ask：LLM 从我的知识库中选 ≤3 个最相关库；单库直通、失败降级全部库）
```

- [ ] **Step 3: CLAUDE.md / AGENTS.md 同步**

- RAG 链路"问答"段：在 ① 之前加"⓪ 路由（/api/ask 专属）"；
- 不变量 #1：补"统一问答的库白名单来自 listMine()，pgvector SQL 仍强制 kb_id IN 过滤"；
- 不变量 #10：补"统一会话 kb_id=NULL（只校验 user_id）；旧按库接口遇 NULL 会话返回 404"；
- 前端小节：`store.chats[kbId]` 分桶改为单一 `store.chat`；接口清单提及 `/api/ask`；
- 实测基准段补一行：统一问答路由实测（日期 + 命中库数）。

- [ ] **Step 4: 汇总变更清单交用户审阅（不 commit）**

`git status`/`git diff --stat` 输出变更摘要，等用户明确指示后才提交。

---

## 自检记录（writer's self-review）

- **spec 覆盖**：spec §4.1→Task 4；§4.2→Task 2；§4.3→Task 5；§4.4→Task 6；§4.5→Task 2（SourceVO）；§5→Task 1；§6→Task 7；§7→Task 6/8/9；§8 风险取舍无代码动作。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码；Task 2 Step 5 的"将原方法体搬入 searchOne"为逐字搬移指令（原文件行号已给出，属可执行的具体动作，非占位）。
- **类型一致性**：`searchByKbs`/`search(Collection)`/`retrieve(List<Long>, …)`/`SourceVO(docId, fileName, kbName, chunkIndex, …)`/`route(question, history, candidates)` 在 Task 2→4→5→6 间签名一致；测试 stub 名与 Task 2 新签名一致。
- **Review Focus 挂载**：① → Task 4 单测 + Task 6 Step 5②；② → Task 5 Step 1④ + Task 6 Step 5①；③ → Task 4 Step 1 解析用例 + LLM 失败降级用例；④ → Task 5 Step 2 代码评审点 + Task 8 Step 1⑦；⑤ → Task 2 Step 10 新单测 + `getOrDefault` 守卫。
