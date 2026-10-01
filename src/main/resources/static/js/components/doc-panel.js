/** 资料管理：上传（LLM 自动分类 → 人工确认）+ 文档列表 + 状态轮询 + 删除 */
window.DocPanel = {
  template: `
    <div class="doc-panel">
      <template v-if="store.currentKbId">
        <div class="doc-head">
          <div class="doc-head-left">
            <el-icon><FolderOpened /></el-icon>
            <span class="doc-head-name">{{ kbName }}</span>
            <span class="doc-head-count">共 {{ store.docTotal }} 个文档</span>
          </div>
          <el-button type="primary" :icon="UploadFilled" @click="openDialog">上传文档</el-button>
        </div>

        <el-table :data="store.docs" v-loading="store.loadingDocs" size="default" class="doc-table">
          <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
          <el-table-column prop="fileType" label="类型" width="80" />
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="片段数" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMsg || '处理失败'"
                          placement="top">
                <el-tag type="danger" size="small">失败</el-tag>
              </el-tooltip>
              <el-tag v-else-if="row.status === 'READY'" type="success" size="small">已就绪</el-tag>
              <el-tag v-else type="warning" size="small">解析中</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="上传时间" width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeDoc(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="这个知识库还没有文档，点右上角上传" :image-size="80" />
          </template>
        </el-table>

        <el-pagination v-if="store.docTotal > store.docSize" class="doc-pager"
                       layout="prev, pager, next, total" :total="store.docTotal"
                       :page-size="store.docSize" :current-page="store.docPage"
                       @current-change="onPage" />
      </template>

      <el-empty v-else description="请先在左侧选择或创建一个知识库" />

      <el-dialog v-model="dialogVisible" title="上传文档" width="540px"
                 :close-on-click-modal="false" :show-close="!uploading"
                 :close-on-press-escape="!uploading">
        <el-upload ref="uploadRef" drag :auto-upload="false" :show-file-list="false"
                   :limit="1" accept=".pdf,.doc,.docx" :on-change="onFileChange"
                   :disabled="uploading || classifying">
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 pdf / doc / docx，单个文件不超过 20MB</div>
          </template>
        </el-upload>

        <div v-if="file" class="up-file">
          已选：<b>{{ file.name }}</b>（{{ fmtSize(file.size) }}）
        </div>

        <div v-if="classifying" class="up-block up-loading">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>正在分析文档内容，推荐知识库…</span>
        </div>
        <div v-else-if="classify" class="up-block">
          <el-alert v-if="classify.recommendedKbId != null" type="success" show-icon :closable="false"
                    :title="'推荐归入《' + classify.recommendedKbName + '》'"
                    :description="classify.reason" />
          <el-alert v-else type="warning" show-icon :closable="false"
                    title="未能自动推荐知识库" :description="classify.reason" />
        </div>

        <div class="up-row">
          <span class="up-label">目标知识库</span>
          <el-select v-model="targetKbId" placeholder="请选择知识库" class="up-select">
            <el-option v-for="kb in store.kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </div>

        <el-collapse v-model="advanced" class="up-advanced">
          <el-collapse-item title="高级选项：分块参数（留空用服务端默认）" name="adv">
            <div class="up-row">
              <span class="up-label">分块大小</span>
              <el-input-number v-model="chunkSize" :min="100" :max="2000" :step="100"
                               placeholder="默认 500" controls-position="right" class="up-num" />
            </div>
            <div class="up-row">
              <span class="up-label">重叠字符</span>
              <el-input-number v-model="chunkOverlap" :min="0" :max="1000" :step="10"
                               placeholder="默认 50" controls-position="right" class="up-num" />
            </div>
          </el-collapse-item>
        </el-collapse>

        <el-alert v-if="uploading" type="info" :closable="false" class="up-block"
                  :title="'正在解析、分块并生成摘要，已用时 ' + elapsed + ' 秒'"
                  description="同步模式下要等全部处理完才返回，大文档可能需要数分钟，请勿关闭页面。" />

        <template #footer>
          <el-button @click="closeDialog" :disabled="uploading">取消</el-button>
          <el-button type="primary" :loading="uploading"
                     :disabled="!file || !targetKbId || classifying" @click="doUpload">
            确认上传
          </el-button>
        </template>
      </el-dialog>
    </div>
  `,
  data() {
    return {
      dialogVisible: false,
      file: null,
      classifying: false,
      classify: null,
      targetKbId: null,
      uploading: false,
      elapsed: 0,
      chunkSize: undefined,
      chunkOverlap: undefined,
      advanced: [],
      pollTimer: null,
      pollIdle: 0,
      UploadFilled: ElementPlusIconsVue.UploadFilled,
      Loading: ElementPlusIconsVue.Loading,
      FolderOpened: ElementPlusIconsVue.FolderOpened
    };
  },
  computed: {
    store() {
      return RagStore.store;
    },
    kbName() {
      const kb = RagStore.currentKb();
      return kb ? kb.name : '';
    }
  },
  watch: {
    'store.currentKbId'() {
      this.stopPolling();
      this.store.docPage = 1;
      this.loadDocs();
    }
  },
  mounted() {
    this.loadDocs();
  },
  unmounted() {
    this.stopPolling();
  },
  methods: {
    fmtSize(bytes) {
      if (bytes == null) return '-';
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      return (bytes / 1024 / 1024).toFixed(2) + ' MB';
    },
    fmtTime(s) {
      return s ? String(s).replace('T', ' ').slice(0, 19) : '-';
    },

    /* ---------- 列表 ---------- */
    async loadDocs() {
      const kbId = this.store.currentKbId;
      if (!kbId) {
        this.store.docs = [];
        this.store.docTotal = 0;
        return;
      }
      this.store.loadingDocs = true;
      try {
        const page = await RagApi.listDocs(kbId, this.store.docPage, this.store.docSize);
        if (kbId !== this.store.currentKbId) return;   // 切库了，丢弃过期响应
        this.store.docs = page.records || [];
        this.store.docTotal = page.total || 0;
      } catch (e) {
        ElMessage.error('加载文档列表失败：' + e.message);
      } finally {
        this.store.loadingDocs = false;
      }
    },
    onPage(p) {
      this.store.docPage = p;
      this.loadDocs();
    },
    async removeDoc(row) {
      try {
        await ElMessageBox.confirm('删除《' + row.fileName + '》会同时删除它的向量片段与摘要。',
          '确认删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
      } catch (e) {
        return;
      }
      try {
        await RagApi.deleteDoc(row.id);
        ElMessage.success('已删除');
        this.loadDocs();
      } catch (e) {
        ElMessage.error(e.message);
      }
    },

    /* ---------- 状态轮询（兼容 async 开与关两种上传模式） ---------- */
    startPolling() {
      if (this.pollTimer) return;
      this.pollIdle = 0;
      this.pollTimer = setInterval(async () => {
        if (!this.store.currentKbId) { this.stopPolling(); return; }
        await this.loadDocs();
        const parsing = this.store.docs.some(d => d.status === 'PARSING');
        this.pollIdle = parsing ? 0 : this.pollIdle + 1;
        if (this.pollIdle >= 2) this.stopPolling();   // 连续两轮没有解析中的就停
      }, 3000);
    },
    stopPolling() {
      if (this.pollTimer) {
        clearInterval(this.pollTimer);
        this.pollTimer = null;
      }
    },

    /* ---------- 上传 ---------- */
    openDialog() {
      if (!this.store.kbs.length) {
        ElMessage.warning('请先创建知识库');
        return;
      }
      this.resetDialog();
      this.targetKbId = this.store.currentKbId;
      this.dialogVisible = true;
    },
    closeDialog() {
      if (this.uploading) return;
      this.dialogVisible = false;
      this.resetDialog();
    },
    resetDialog() {
      this.file = null;
      this.classify = null;
      this.classifying = false;
      this.chunkSize = undefined;
      this.chunkOverlap = undefined;
      this.advanced = [];
      if (this.$refs.uploadRef) this.$refs.uploadRef.clearFiles();
    },
    async onFileChange(uploadFile) {
      const raw = uploadFile.raw;
      if (!raw) return;
      // 前端先自查一遍，省一次必然失败的请求
      const ext = (raw.name.split('.').pop() || '').toLowerCase();
      if (!['pdf', 'doc', 'docx'].includes(ext)) {
        ElMessage.error('仅支持 pdf / doc / docx 格式');
        this.$refs.uploadRef.clearFiles();
        return;
      }
      if (raw.size > 20 * 1024 * 1024) {
        ElMessage.error('文件超过 20MB 限制');
        this.$refs.uploadRef.clearFiles();
        return;
      }

      this.file = raw;
      this.classify = null;
      this.classifying = true;
      try {
        const result = await RagApi.classify(raw);
        this.classify = result;
        this.targetKbId = result.recommendedKbId != null
          ? result.recommendedKbId : this.store.currentKbId;
      } catch (e) {
        // 分类接口本身报错（文件非法等）不该阻断上传：退化为纯手动选库
        this.classify = { recommendedKbId: null, reason: '自动分类不可用：' + e.message };
        this.targetKbId = this.store.currentKbId;
      } finally {
        this.classifying = false;
      }
    },
    async doUpload() {
      if (!this.file || !this.targetKbId || this.uploading) return;
      this.uploading = true;
      this.elapsed = 0;
      const timer = setInterval(() => { this.elapsed++; }, 1000);
      try {
        const doc = await RagApi.upload(this.file, this.targetKbId,
          this.chunkSize, this.chunkOverlap);
        ElMessage.success('《' + doc.fileName + '》处理完成，共 ' + doc.chunkCount + ' 个片段');
        this.uploading = false;
        this.closeDialog();
        if (this.targetKbId === this.store.currentKbId) {
          this.store.docPage = 1;
          await this.loadDocs();
          this.startPolling();
        }
      } catch (e) {
        ElMessage.error(e.message);
        // 后端失败补偿可能已经落了一条 FAILED 记录，刷新能看到
        if (this.targetKbId === this.store.currentKbId) {
          await this.loadDocs();
          this.startPolling();
        }
      } finally {
        clearInterval(timer);
        this.uploading = false;
      }
    }
  }
};
