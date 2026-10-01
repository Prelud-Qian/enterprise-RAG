/** 登录 / 注册：未登录时全屏显示。校验规则与后端 RegisterRequest 保持一致，少一次 400 往返 */
window.LoginView = {
  emits: ['logged-in'],
  template: `
    <div class="login-page">
      <div class="login-card">
        <h1 class="login-title">企业知识库问答</h1>
        <p class="login-sub">RAG 检索增强 · 引用溯源 · 多轮追问</p>

        <el-tabs v-model="mode" stretch>
          <el-tab-pane label="登录" name="login" />
          <el-tab-pane label="注册" name="register" />
        </el-tabs>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top"
                 @submit.prevent="submit">
          <el-form-item prop="username">
            <el-input v-model="form.username" size="large" placeholder="用户名"
                      :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" size="large" type="password" show-password
                      placeholder="密码" :prefix-icon="Lock" @keyup.enter="submit" />
          </el-form-item>
          <el-button type="primary" size="large" class="login-btn"
                     :loading="loading" @click="submit">
            {{ mode === 'login' ? '登 录' : '注册并登录' }}
          </el-button>
        </el-form>
      </div>
    </div>
  `,
  data() {
    return {
      mode: 'login',
      loading: false,
      form: { username: '', password: '' },
      User: ElementPlusIconsVue.User,
      Lock: ElementPlusIconsVue.Lock,
      rules: {
        username: [
          { required: true, message: '请输入用户名', trigger: 'blur' },
          { pattern: /^[a-zA-Z0-9_]{3,50}$/, message: '3~50 位字母、数字或下划线', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 32, message: '密码长度 6~32 位', trigger: 'blur' }
        ]
      }
    };
  },
  methods: {
    async submit() {
      const ok = await this.$refs.formRef.validate().catch(() => false);
      if (!ok) return;
      this.loading = true;
      const { username, password } = this.form;
      try {
        if (this.mode === 'register') {
          await RagApi.register(username, password);
          ElMessage.success('注册成功，正在登录…');
        }
        const data = await RagApi.login(username, password);
        RagApi.setAuth(data.token, data.user);
        this.$emit('logged-in');
      } catch (e) {
        ElMessage.error(e.message);
      } finally {
        this.loading = false;
      }
    }
  }
};
