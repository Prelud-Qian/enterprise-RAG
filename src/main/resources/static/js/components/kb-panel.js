/** 侧边栏：知识库列表 / 新建 / 删除 / 切换。所有按 kb 的接口在服务端都会再校验一次归属 */
window.KbPanel = {
  template: `
    <div class="kb-panel">
      <div class="kb-head">
        <span class="kb-head-title">知识库</span>
        <el-button type="primary" link :icon="Plus" @click="openCreate">新建</el-button>
      </div>

      <div class="kb-list">
        <el-empty v-if="!store.kbs.length" description="还没有知识库" :image-size="60" />
        <div v-for="kb in store.kbs" :key="kb.id"
             class="kb-item" :class="{ active: kb.id === store.currentKbId }"
             @click="select(kb)">
          <el-icon class="kb-item-icon"><Collection /></el-icon>
          <div class="kb-item-main">
            <div class="kb-item-name">{{ kb.name }}</div>
            <div class="kb-item-desc">{{ kb.description || '暂无描述' }}</div>
          </div>
          <el-icon class="kb-item-del" @click.stop="remove(kb)"><Delete /></el-icon>
        </div>
      </div>

      <el-dialog v-model="dialogVisible" title="新建知识库" width="420px"
                 :close-on-click-modal="false">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" maxlength="100" show-word-limit placeholder="如：员工手册" />
          </el-form-item>
          <el-form-item label="描述" prop="description">
            <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500"
                      show-word-limit placeholder="一句话说明这个库放什么内容，自动分类会参考它" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="create">创建</el-button>
        </template>
      </el-dialog>
    </div>
  `,
  data() {
    return {
      dialogVisible: false,
      saving: false,
      form: { name: '', description: '' },
      Plus: ElementPlusIconsVue.Plus,
      Delete: ElementPlusIconsVue.Delete,
      Collection: ElementPlusIconsVue.Collection,
      rules: {
        name: [{ required: true, message: '请输入知识库名称', trigger: 'blur' }]
      }
    };
  },
  computed: {
    store() {
      return RagStore.store;
    }
  },
  methods: {
    select(kb) {
      RagStore.store.currentKbId = kb.id;
    },
    openCreate() {
      this.form = { name: '', description: '' };
      this.dialogVisible = true;
    },
    async create() {
      const ok = await this.$refs.formRef.validate().catch(() => false);
      if (!ok) return;
      this.saving = true;
      try {
        const kb = await RagApi.createKb(this.form.name, this.form.description);
        RagStore.store.kbs.unshift(kb);
        RagStore.store.currentKbId = kb.id;   // 建完直接切过去，省一步点击
        this.dialogVisible = false;
        ElMessage.success('知识库《' + kb.name + '》已创建');
      } catch (e) {
        ElMessage.error(e.message);
      } finally {
        this.saving = false;
      }
    },
    async remove(kb) {
      try {
        await ElMessageBox.confirm(
          '删除《' + kb.name + '》会同时删除库内全部文档、向量片段与摘要，且不可恢复。',
          '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
      } catch (e) {
        return; // 用户取消
      }
      try {
        await RagApi.deleteKb(kb.id);
        const store = RagStore.store;
        store.kbs = store.kbs.filter(k => k.id !== kb.id);
        if (store.currentKbId === kb.id) {
          store.currentKbId = store.kbs.length ? store.kbs[0].id : null;
        }
        ElMessage.success('已删除');
      } catch (e) {
        ElMessage.error(e.message);
      }
    }
  }
};
