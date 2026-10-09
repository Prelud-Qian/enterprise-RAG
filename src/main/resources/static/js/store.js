/**
 * 全局状态（Vue.reactive 单例）。
 * chat 是唯一会话状态：一个窗口一份历史（mode=auto 自动选库 / kb 指定知识库，切模式或切库会开新会话）。
 */
(function (global) {
  'use strict';

  const store = Vue.reactive({
    ready: false,        // 首屏引导是否完成
    user: null,          // 当前用户（仅用于展示，权限以服务端为准）
    kbs: [],             // 知识库列表
    currentKbId: null,   // 当前选中的知识库
    view: 'chat',        // chat | docs

    // 唯一会话状态：mode=auto 自动选库（统一问答）/ kb 指定知识库；kbId 仅 kb 模式使用
    chat: { mode: 'auto', kbId: null, conversationId: null, messages: [], streaming: false },

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
    store.chat = { mode: 'auto', kbId: null, conversationId: null, messages: [], streaming: false };
    store.docs = [];
    store.docTotal = 0;
    store.docPage = 1;
  }

  global.RagStore = { store, currentKb, reset };
})(window);
