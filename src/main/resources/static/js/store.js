/**
 * 全局状态（Vue.reactive 单例）。
 * chat 是唯一会话状态：统一问答不按知识库分桶，一个窗口一份历史。
 */
(function (global) {
  'use strict';

  const store = Vue.reactive({
    ready: false,        // 首屏引导是否完成
    user: null,          // 当前用户（仅用于展示，权限以服务端为准）
    kbs: [],             // 知识库列表
    currentKbId: null,   // 当前选中的知识库
    view: 'chat',        // chat | docs

    chat: { conversationId: null, messages: [], streaming: false },   // 唯一会话状态（统一问答：一个窗口）

    docs: [],            // 当前库的文档列表
    docTotal: 0,
    docPage: 1,
    docSize: 10,
    loadingDocs: false
  });

  function currentKb() {
    return store.kbs.find(k => k.id === store.currentKbId) || null;
  }

  /** 退出登录：清空全部内存状态 */
  function reset() {
    store.ready = false;
    store.user = null;
    store.kbs = [];
    store.currentKbId = null;
    store.view = 'chat';
    store.chat = { conversationId: null, messages: [], streaming: false };
    store.docs = [];
    store.docTotal = 0;
    store.docPage = 1;
  }

  global.RagStore = { store, currentKb, reset };
})(window);
