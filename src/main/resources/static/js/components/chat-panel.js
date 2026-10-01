/** 对话面板：流式渲染 + 引用溯源 + 多轮追问 */
window.ChatPanel = {
  template: `
    <div class="chat-panel">
      <div class="chat-head">
        <div class="chat-head-left">
          <el-icon><ChatDotRound /></el-icon>
          <span class="chat-head-name">统一问答</span>
          <el-tag v-if="chat.conversationId" size="small" type="info" effect="plain">
            会话 #{{ chat.conversationId }}
          </el-tag>
        </div>
        <el-button link :icon="RefreshLeft" @click="newChat"
                   :disabled="chat.streaming">新对话</el-button>
      </div>

      <div class="chat-body" ref="scroller">
        <el-empty v-if="!chat.messages.length"
                  description="直接提问，系统会自动判断检索哪些知识库" :image-size="90" />

        <div v-for="(m, i) in chat.messages" :key="i" class="msg" :class="m.role">
          <div class="avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="msg-main">
            <div class="bubble">
              <span class="bubble-text">{{ m.content }}</span><span
                    v-if="m.streaming" class="caret"></span>
              <div v-if="m.error" class="bubble-error">
                <el-icon><WarningFilled /></el-icon><span>{{ m.error }}</span>
              </div>
              <div v-else-if="m.fallback" class="bubble-note">
                未在知识库中找到相关资料
              </div>
              <div v-if="m.stopped" class="bubble-note">已停止生成</div>
            </div>

            <el-collapse v-if="m.sources && m.sources.length" class="src-collapse">
              <el-collapse-item :name="i">
                <template #title>
                  <span class="src-title">引用来源（{{ m.sources.length }}）</span>
                </template>
                <div v-for="(s, j) in m.sources" :key="j" class="src-item">
                  <div class="src-head">
                    <span class="src-file">《{{ s.fileName }}》第 {{ s.chunkIndex }} 段</span>
                    <el-tag v-if="s.kbName" size="small" type="info" effect="plain">{{ s.kbName }}</el-tag>
                    <span v-if="s.score != null" class="src-score">score {{ s.score.toFixed(3) }}</span>
                  </div>
                  <div v-if="s.headingPath" class="src-path">{{ s.headingPath }}</div>
                  <div v-if="s.matchedTerms && s.matchedTerms.length" class="src-terms">
                    <el-tag v-for="t in s.matchedTerms" :key="t" size="small"
                            type="warning" effect="plain">{{ t }}</el-tag>
                  </div>
                  <div class="src-content">{{ s.content }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </div>

      <div class="chat-foot">
        <el-input v-model="draft" type="textarea" :rows="2" resize="none"
                  :disabled="chat.streaming"
                  placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                  @keydown.enter="onEnter" />
        <el-button v-if="chat.streaming" type="danger" plain class="send-btn"
                   @click="stop">停止生成</el-button>
        <el-button v-else type="primary" class="send-btn"
                   :disabled="!draft.trim()" @click="send">发送</el-button>
      </div>
    </div>
  `,
  data() {
    return {
      draft: '',
      ctrl: null,
      ChatDotRound: ElementPlusIconsVue.ChatDotRound,
      RefreshLeft: ElementPlusIconsVue.RefreshLeft,
      WarningFilled: ElementPlusIconsVue.WarningFilled
    };
  },
  computed: {
    store() {
      return RagStore.store;
    },
    chat() {
      return RagStore.store.chat;
    }
  },
  methods: {
    onEnter(e) {
      // isComposing：中文输入法候选框里的回车不能当成发送
      if (e.isComposing || e.shiftKey) return;
      e.preventDefault();
      this.send();
    },
    newChat() {
      const chat = this.store.chat;
      chat.conversationId = null;   // 下一问后端会新建会话，meta 事件回传新 id
      chat.messages = [];
      this.draft = '';
    },
    stop() {
      const chat = this.chat;
      const last = chat.messages[chat.messages.length - 1];
      if (last && last.role === 'assistant') last.stopped = true;
      if (this.ctrl) this.ctrl.abort();
    },
    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.scroller;
        if (el) el.scrollTop = el.scrollHeight;
      });
    },
    async send() {
      const text = this.draft.trim();
      const chat = this.chat;
      if (!text || chat.streaming) return;

      chat.messages.push({ role: 'user', content: text });
      // 必须包一层 reactive：push 原始对象进 reactive 数组后，直接改这个局部变量
      // 改的是 raw target、绕过 proxy 的 setter，模板收不到通知（流式文字会不显示）
      const msg = Vue.reactive({ role: 'assistant', content: '', sources: [], error: '', streaming: true });
      chat.messages.push(msg);
      chat.streaming = true;
      this.draft = '';
      this.scrollToBottom();

      this.ctrl = new AbortController();
      try {
        await RagApi.askStream(text, chat.conversationId, {
          onMeta: m => {
            if (m && m.conversationId) chat.conversationId = m.conversationId;
          },
          onMessage: t => {
            // 追加前判断用户是否贴着底部，避免他往上翻看时被强行拽回
            const el = this.$refs.scroller;
            const stick = !el || (el.scrollHeight - el.scrollTop - el.clientHeight < 120);
            msg.content += t;
            if (stick) this.scrollToBottom();
          },
          onSources: s => {
            msg.sources = s;
            if (!s.length) msg.fallback = true;
          },
          onError: e => {
            msg.error = e.message;
          }
        }, this.ctrl.signal);
      } catch (e) {
        if (e.code === 404) {
          // 会话失效自愈：conversationId 已不存在或不属于当前用户。
          // 不自动重发——重发会再吃一次限流配额，让用户自己决定
          chat.conversationId = null;
          msg.error = '会话已失效，已为你开启新会话，请重新提问';
        } else if (e.code === 429) {
          msg.error = '请求过于频繁（提问与流式共用 10 次/分钟），请稍后再试';
        } else {
          msg.error = e.message;
        }
      } finally {
        chat.streaming = false;
        msg.streaming = false;
        this.ctrl = null;
        this.scrollToBottom();
      }
    }
  }
};
