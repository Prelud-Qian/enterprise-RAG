/** 根组件与首屏引导：未登录 → 登录页；已登录 → 头部 + 知识库侧栏 + 对话/资料主区 */
(function () {
  'use strict';

  // Element Plus 的 UMD 包只导出 window.ElementPlus，不会创建全局 ElMessage / ElMessageBox。
  // 各组件里直接用 ElMessage.xxx / ElMessageBox.confirm，这里显式转挂，否则运行时 ReferenceError
  window.ElMessage = ElementPlus.ElMessage;
  window.ElMessageBox = ElementPlus.ElMessageBox;

  const store = RagStore.store;

  const AppRoot = {
    template: `
      <div v-if="!store.ready" class="boot">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中…</span>
      </div>

      <login-view v-else-if="!store.user" @logged-in="onLoggedIn" />

      <el-container v-else class="layout">
        <el-header class="app-header" height="56px">
          <div class="brand">
            <el-icon><Notebook /></el-icon>
            <span>企业知识库问答</span>
          </div>
          <div class="header-right">
            <el-tag size="small" effect="plain" type="info">{{ store.user.role }}</el-tag>
            <span class="username">{{ store.user.username }}</span>
            <el-button link @click="logout">退出</el-button>
          </div>
        </el-header>

        <el-container class="layout-body">
          <el-aside width="264px" class="app-aside"><kb-panel /></el-aside>
          <el-main class="app-main">
            <el-tabs v-model="store.view" class="main-tabs">
              <el-tab-pane label="对话" name="chat" />
              <el-tab-pane label="资料管理" name="docs" />
            </el-tabs>
            <div class="main-body">
              <!-- v-show 而不是 v-if：两个面板都保持挂载，切 tab 不会丢会话和轮询状态 -->
              <chat-panel v-show="store.view === 'chat'" />
              <doc-panel v-show="store.view === 'docs'" />
            </div>
          </el-main>
        </el-container>
      </el-container>
    `,
    data() {
      return {
        Loading: ElementPlusIconsVue.Loading,
        Notebook: ElementPlusIconsVue.Notebook
      };
    },
    computed: {
      store() {
        return store;
      }
    },
    methods: {
      async bootstrap() {
        if (!RagApi.getToken()) {
          store.ready = true;
          return;
        }
        store.user = RagApi.getUser();   // 先用本地缓存顶上，me 失败再清
        try {
          store.user = await RagApi.me();
          await this.loadKbs();
        } catch (e) {
          if (e.code !== 401) {
            ElMessage.error('初始化失败：' + e.message);
          }
          // 401 已由 api 层清掉 token 并触发退出，这里不重复处理
        } finally {
          store.ready = true;
        }
      },
      async loadKbs() {
        store.kbs = await RagApi.listKb();
        if (store.kbs.length && !store.currentKbId) {
          store.currentKbId = store.kbs[0].id;
        }
      },
      onLoggedIn() {
        store.ready = false;
        this.bootstrap();
      },
      logout() {
        RagApi.clearAuth();
        RagStore.reset();
        store.ready = true;
      }
    },
    mounted() {
      RagApi.onUnauthorized(() => {
        RagStore.reset();
        store.ready = true;
        ElMessage.warning('登录已过期，请重新登录');
      });
      this.bootstrap();
    }
  };

  const app = Vue.createApp(AppRoot);
  app.use(ElementPlus, { locale: ElementPlusLocaleZhCn });   // 分页/上传等内置文案走中文
  Object.keys(ElementPlusIconsVue || {}).forEach(name => {
    app.component(name, ElementPlusIconsVue[name]);
  });
  app.component('login-view', window.LoginView);
  app.component('kb-panel', window.KbPanel);
  app.component('chat-panel', window.ChatPanel);
  app.component('doc-panel', window.DocPanel);
  app.mount('#app');
})();
