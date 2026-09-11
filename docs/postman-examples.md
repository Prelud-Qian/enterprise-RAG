# Postman 接口调用示例

> **可直接导入的 Postman Collection**：[docs/postman/enterprise-rag.postman_collection.json](postman/enterprise-rag.postman_collection.json)
> 导入后把变量 `baseUrl` 改成实际地址（默认 http://localhost:9090），按 Auth → KnowledgeBase → Document → Qa 顺序执行：登录后 token 自动写入变量，建库后 kbId 自动写入。集合内含断言脚本（登录成功、上传 READY、兜底话术、ADMIN 403 等），点 Runner 一键跑全链路。

以下为等价 curl 示例，便于命令行执行。`{{token}}` 为登录接口返回的 JWT，放在请求头 `Authorization: Bearer {{token}}`。

> 也可直接用 Swagger UI 在线调试：`http://localhost:8080/swagger-ui.html`（右上角 Authorize 填 JWT）。

## 前置条件

1. MySQL / PostgreSQL(pgvector) 已建库建表（见 `sql/` 目录脚本）
2. 已配置 `DASHSCOPE_API_KEY` 环境变量
3. 服务已启动：`mvn spring-boot:run`（端口 8080）

---

## 1. 认证模块

### 1.1 注册

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"123456"}'
```

响应：

```json
{ "code": 200, "message": "success", "data": 1 }
```

### 1.2 登录（拿到 token 后所有接口都要带）

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"123456"}'
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.xxx.xxx",
    "user": { "id": 1, "username": "alice", "role": "USER", "enabled": 1, "createdAt": "2026-09-07 20:30:00" }
  }
}
```

### 1.3 当前用户

```bash
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer {{token}}"
```

---

## 2. 知识库模块

### 2.1 创建知识库

```bash
curl -X POST http://localhost:8080/api/kb \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{"name":"员工手册知识库","description":"公司制度与福利政策"}'
```

### 2.2 我的知识库列表

```bash
curl http://localhost:8080/api/kb \
  -H "Authorization: Bearer {{token}}"
```

### 2.3 删除知识库（级联删除文档与向量片段）

```bash
curl -X DELETE http://localhost:8080/api/kb/1 \
  -H "Authorization: Bearer {{token}}"
```

---

## 3. 文档上传模块（RAG 入库链路）

### 3.1 上传 PDF/Word（用默认分块参数）

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer {{token}}" \
  -F "kbId=1" \
  -F "file=@D:/docs/员工手册.pdf"
```

> 若 yml 配了 `rag.upload.async: true`，此接口秒返回 status=PARSING，轮询 `GET /api/documents?kbId=1` 直到 READY/FAILED（失败原因在 errorMsg）。

### 3.2 上传并覆盖分块参数

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer {{token}}" \
  -F "kbId=1" \
  -F "chunkSize=300" \
  -F "chunkOverlap=30" \
  -F "file=@D:/docs/考勤制度.docx"
```

响应（READY 表示解析→分块→向量化→入库全部完成）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1, "kbId": 1, "fileName": "员工手册.pdf", "fileType": "pdf",
    "fileSize": 204857, "chunkCount": 87, "status": "READY", "errorMsg": null,
    "createdAt": "2026-09-07 20:35:12"
  }
}
```

### 3.3 文档列表

```bash
curl "http://localhost:8080/api/documents?kbId=1&page=1&size=10" \
  -H "Authorization: Bearer {{token}}"
```

### 3.4 删除文档（级联删除 pgvector 片段 + 重建 BM25 索引）

```bash
curl -X DELETE http://localhost:8080/api/documents/1 \
  -H "Authorization: Bearer {{token}}"
```

---

## 4. 问答模块（RAG 检索链路 + 溯源 + 兜底）

### 4.1 正常提问（命中知识 → 返回答案 + 引用来源）

```bash
curl -X POST http://localhost:8080/api/kb/1/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{"question":"员工年假有多少天？"}'
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "根据员工手册，员工入职满一年后享有 5 天带薪年假，逐年递增，上限 15 天。参考：【来源1】",
    "isFallback": false,
    "sources": [
      {
        "docId": 1,
        "fileName": "员工手册.pdf",
        "chunkIndex": 12,
        "content": "第五章 休假制度 …年假…",
        "score": 0.0309
      }
    ]
  }
}
```

### 4.2 仅检索（不调 LLM，评测/调试用）

```bash
curl -X POST http://localhost:8080/api/kb/1/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{"question":"员工年假有多少天？"}'
```

响应（sources 按精排/RRF 得分排序，maxSimilarity 供兜底阈值调优参考）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "maxSimilarity": 0.83,
    "sources": [
      { "docId": 1, "fileName": "员工手册.pdf", "chunkIndex": 12, "content": "第五章 休假制度…", "score": 0.92 }
    ]
  }
}
```

> 配套评测脚本：`python docs/eval/eval.py --token {{token}} --kb 1 --k 5`，输出 Hit@5 命中率报告。

### 4.3 流式提问（SSE，逐 token 返回）

```bash
curl -N -X POST http://localhost:8080/api/kb/1/ask/stream \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{"question":"员工年假有多少天？"}'
```

响应（`event: message` 逐 token 推送，最后 `event: sources` 携带溯源 JSON）：

```text
event: message
data: 根据员工手册

event: message
data: ，员工入职满一年后享有 5 天带薪年假...

event: sources
data: [{"docId":1,"fileName":"员工手册.pdf","chunkIndex":12,"content":"...","score":0.92}]
```

### 4.4 幻觉兜底验证（问库外问题 → 不调 LLM，直接返回固定话术）

```bash
curl -X POST http://localhost:8080/api/kb/1/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{token}}" \
  -d '{"question":"量子力学中的薛定谔方程怎么推导？"}'
```

响应（`isFallback: true`，sources 为空）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "没有找到相关资料，请换个问法或先上传相关文档。",
    "isFallback": true,
    "sources": []
  }
}
```

### 4.5 问答日志（审计）

```bash
curl "http://localhost:8080/api/kb/1/qa-logs?page=1&size=10" \
  -H "Authorization: Bearer {{token}}"
```

---

## 5. RBAC 越权验证（面试演示用）

用 **alice** 建的知识库，换 **bob** 的 token 去访问，应该拿到 403：

```bash
# 1) 注册 bob 并登录拿到 bob 的 token
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"123456"}'

# 2) bob 访问 alice 的知识库 → 403 无权访问该知识库
curl http://localhost:8080/api/kb/1 \
  -H "Authorization: Bearer {{bob_token}}"
# → { "code": 403, "message": "无权访问该知识库", "data": null }

# 3) bob 用 alice 的知识库提问 → 同样 403
curl -X POST http://localhost:8080/api/kb/1/ask \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer {{bob_token}}" \
  -d '{"question":"员工年假有多少天？"}'

# 4) bob 调管理员接口 → 403 无权限访问
curl http://localhost:8080/api/admin/users \
  -H "Authorization: Bearer {{bob_token}}"

# 5) 不带 token 调任意接口 → 401 未登录或登录已过期
curl http://localhost:8080/api/kb
```

## 6. ADMIN 提权与验证

```sql
-- MySQL 中执行，把 alice 提为管理员
UPDATE sys_user SET role = 'ADMIN' WHERE username = 'alice';
```

重新登录 alice 后调 `/api/admin/users` 可拿到用户列表（ADMIN 也可以访问所有人的知识库）。

---

## 异常返回示例

| 场景 | HTTP 状态码 | body |
|---|---|---|
| 用户名已存在 | 400 | `{"code":400,"message":"用户名已存在","data":null}` |
| 上传超限 | 400 | `{"code":400,"message":"上传文件大小超出限制","data":null}` |
| 未带 token | 401 | `{"code":401,"message":"未登录或登录已过期","data":null}` |
| 越权访问 | 403 | `{"code":403,"message":"无权访问该知识库","data":null}` |
| 知识库不存在 | 404 | `{"code":404,"message":"知识库不存在","data":null}` |
| 服务异常 | 500 | `{"code":500,"message":"系统内部错误：...","data":null}` |
