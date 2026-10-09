/**
 * 后端接口封装：统一 fetch + Result 解包 + SSE 流式解析。
 * 不依赖 Vue，可单独在浏览器控制台或 node 里测。
 *
 * 约定：后端所有接口返回 Result{code,message,data}，code=200 为成功且与 HTTP 状态码一致；
 * 唯一例外是 /ask/stream —— 它返回 SSE 流，但流开始前抛出的同步异常（401/403/404/429/400）
 * 仍是普通 JSON，所以流式函数里必须先判内容类型再决定按哪种方式解析。
 */
(function (global) {
  'use strict';

  const TOKEN_KEY = 'rag_token';
  const USER_KEY = 'rag_user';

  /* ==================== 登录态 ==================== */

  function getToken() {
    return localStorage.getItem(TOKEN_KEY) || '';
  }

  function getUser() {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null');
    } catch (e) {
      return null;
    }
  }

  function setAuth(token, user) {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user || null));
  }

  function clearAuth() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  let unauthorizedHandler = null;

  /** 注册 401 回调（app 用来清空状态并跳回登录页） */
  function onUnauthorized(fn) {
    unauthorizedHandler = fn;
  }

  function notifyUnauthorized() {
    if (unauthorizedHandler) {
      unauthorizedHandler();
    }
  }

  function authHeaders(extra) {
    const headers = Object.assign({}, extra || {});
    const token = getToken();
    if (token) {
      headers['Authorization'] = 'Bearer ' + token;
    }
    return headers;
  }

  /* ==================== 统一请求 ==================== */

  /**
   * 成功返回 Result.data，失败抛 Error（带上 code）。
   * 以 body.code 为准而不是 HTTP 状态码：两者语义一致，但 body.code 更可靠。
   */
  async function request(path, options) {
    const opt = options || {};
    const headers = authHeaders();
    let body = opt.body;

    if (body !== undefined && !opt.isForm) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(body);
    }
    // isForm 时刻意不设 Content-Type：浏览器要自己带 multipart boundary

    const res = await fetch(path, { method: opt.method || 'GET', headers, body, signal: opt.signal });

    const text = await res.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch (e) {
      // 非 JSON 响应体（网关错误页等），下面走默认文案
    }

    if (!res.ok || !json || json.code !== 200) {
      const code = (json && json.code) || res.status;
      const message = (json && json.message) || ('请求失败（HTTP ' + res.status + '）');
      if (code === 401) {
        clearAuth();
        notifyUnauthorized();
      }
      const err = new Error(message);
      err.code = code;
      throw err;
    }
    return json.data;
  }

  /* ==================== SSE 帧解析 ==================== */

  // SSE 以空行分隔事件帧；Spring 用 \n，这里兼容 \r\n
  const FRAME_SEP = /\r?\n\r?\n/;

  /**
   * 解析一帧 SSE 文本并分发。
   * 关键：Spring 会把 data 里的换行转义成多条 data: 行（SseEventBuilderImpl.data() 的行为），
   * 所以多条 data 行必须用 \n 拼回，只取第一条会让所有分段回答被截断。
   */
  function dispatchFrame(frame, cb) {
    let event = 'message'; // 无 event 字段时 SSE 默认事件名是 message
    const dataLines = [];

    for (const line of frame.split(/\r\n|\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, '')); // 规范：冒号后一个空格要去掉
      }
      // id: / retry: / 注释行忽略
    }

    const data = dataLines.join('\n');

    if (event === 'meta') {
      let meta = {};
      try {
        meta = JSON.parse(data);
      } catch (e) {
        meta = {};
      }
      if (cb.onMeta) cb.onMeta(meta);
    } else if (event === 'message') {
      if (cb.onMessage) cb.onMessage(data); // 纯文本 token，不 parse
    } else if (event === 'sources') {
      let list = [];
      try {
        list = JSON.parse(data);
      } catch (e) {
        list = [];
      }
      if (cb.onSources) cb.onSources(Array.isArray(list) ? list : []);
    } else if (event === 'error') {
      if (cb.onError) cb.onError(new Error(data));
    }
  }

  /**
   * 流式提问公共实现（统一问答 / 按库问答共用）。后端事件序列：meta(会话 id) → message(token) × N → sources(SourceVO[]) → 关闭。
   * 流中的错误走 onError 回调；只有「流还没起来就失败」（未登录 401 / 会话 404 / 未创建知识库 400 / 限流 429）
   * 才 throw —— 那时后端返回的是普通 JSON，不是 SSE。
   */
  async function streamAsk(url, body, handlers, signal) {
    const cb = handlers || {};

    const res = await fetch(url, {
      method: 'POST',
      headers: authHeaders({
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream'
      }),
      body: JSON.stringify(body),
      signal: signal
    });

    // ① 流没起来：按 Result JSON 解析错误（此时 emitter 还没开始推事件，走的是统一异常通道）
    const contentType = res.headers.get('Content-Type') || '';
    if (!res.ok || contentType.indexOf('text/event-stream') < 0) {
      let code = res.status;
      let message = '请求失败（HTTP ' + res.status + '）';
      try {
        const body = await res.json();
        if (body && body.message) {
          message = body.message;
          code = body.code || code;
        }
      } catch (e) {
        // 非 JSON：沿用默认文案
      }
      if (code === 401) {
        clearAuth();
        notifyUnauthorized();
      }
      const err = new Error(message);
      err.code = code;
      throw err;
    }

    // ② 逐帧读流
    const reader = res.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) break;
        // stream:true —— 多字节汉字被切在 chunk 边界时不会解码成乱码
        buffer += decoder.decode(chunk.value, { stream: true });

        let m;
        while ((m = FRAME_SEP.exec(buffer)) !== null) {
          const frame = buffer.slice(0, m.index);
          buffer = buffer.slice(m.index + m[0].length);
          if (frame.trim()) dispatchFrame(frame, cb);
        }
      }
      buffer += decoder.decode(); // 冲刷解码器尾部
      if (buffer.trim()) dispatchFrame(buffer, cb); // 服务端关闭前没补空行的残帧
      if (cb.onDone) cb.onDone();
    } catch (e) {
      if (e.name === 'AbortError') return; // 用户点了「停止生成」
      if (cb.onError) cb.onError(e);
    }
  }

  /** 统一问答（自动选库）：POST /api/ask/stream */
  function askStream(question, conversationId, handlers, signal) {
    return streamAsk('/api/ask/stream', {
      question: question,
      conversationId: conversationId == null ? null : conversationId
    }, handlers, signal);
  }

  /** 按库问答（指定知识库）：POST /api/kb/{kbId}/ask/stream，事件与统一问答完全一致 */
  function askStreamKb(kbId, question, conversationId, handlers, signal) {
    return streamAsk('/api/kb/' + kbId + '/ask/stream', {
      question: question,
      conversationId: conversationId == null ? null : conversationId
    }, handlers, signal);
  }

  /* ==================== 业务接口 ==================== */

  const api = {
    getToken, getUser, setAuth, clearAuth, onUnauthorized,

    login: (username, password) =>
      request('/api/auth/login', { method: 'POST', body: { username, password } }),

    register: (username, password) =>
      request('/api/auth/register', { method: 'POST', body: { username, password } }),

    me: () => request('/api/auth/me'),

    listKb: () => request('/api/kb'),

    createKb: (name, description) =>
      request('/api/kb', { method: 'POST', body: { name, description } }),

    deleteKb: (id) => request('/api/kb/' + id, { method: 'DELETE' }),

    listDocs: (kbId, page, size) =>
      request('/api/documents?kbId=' + kbId + '&page=' + (page || 1) + '&size=' + (size || 10)),

    deleteDoc: (id) => request('/api/documents/' + id, { method: 'DELETE' }),

    /** 上传前自动分类：multipart，只传 file。失败降级为「不推荐」，不会抛错 */
    classify: (file, signal) => {
      const fd = new FormData();
      fd.append('file', file);
      return request('/api/documents/classify', { method: 'POST', body: fd, isForm: true, signal });
    },

    /** 上传入库：multipart，isForm=true 时不能手写 Content-Type，否则 boundary 丢了 */
    upload: (file, kbId, chunkSize, chunkOverlap, signal) => {
      const fd = new FormData();
      fd.append('file', file);
      fd.append('kbId', kbId);
      if (chunkSize) fd.append('chunkSize', chunkSize);
      if (chunkOverlap !== undefined && chunkOverlap !== null && chunkOverlap !== '') {
        fd.append('chunkOverlap', chunkOverlap);
      }
      return request('/api/documents/upload', { method: 'POST', body: fd, isForm: true, signal });
    },

    askStream,
    askStreamKb
  };

  global.RagApi = api;
})(typeof window !== 'undefined' ? window : globalThis);
