package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.common.LoginUser;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.DocumentMapper;
import com.enterprise.rag.dao.pg.ChunkRecord;
import com.enterprise.rag.dao.pg.SummaryDao;
import com.enterprise.rag.dao.pg.SummaryRecord;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.entity.Document;
import com.enterprise.rag.entity.vo.DocumentVO;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * 文档上传 → RAG 入库全流程（同步）
 *
 * 【RAG 第 1-4 步】文档解析 → 语义分块 → 向量化 → 入库 pgvector，
 * 详见 upload() 内逐步注释。
 *
 * 一致性说明：MySQL 业务库与 PG 向量库无法用单库事务，
 * 采用「文档状态机（PARSING/READY/FAILED）+ 失败补偿」保证最终一致
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final VectorStoreDao vectorStoreDao;
    private final SummaryDao summaryDao;
    private final SummaryService summaryService;
    private final Bm25IndexService bm25IndexService;
    private final DocumentMapper documentMapper;
    private final RagProperties props;
    /** 文档处理线程池（rag.upload.async=true 时后台处理用） */
    private final ExecutorService uploadExecutor;

    public DocumentVO upload(Long kbId, MultipartFile file, Integer chunkSizeParam, Integer chunkOverlapParam) {
        // 权限校验：只能给自己的知识库传文档
        knowledgeBaseService.requireAccess(kbId);
        LoginUser user = SecurityUtil.currentUser();

        // 文件校验：文件名清洗（防路径穿越）、扩展名白名单、大小限制
        String fileName = StringUtils.cleanPath(
                Objects.requireNonNull(file.getOriginalFilename(), "文件名不能为空"));
        String ext = StringUtils.getFilenameExtension(fileName);
        if (ext == null || !props.getUpload().getAllowedExtensions().contains(ext.toLowerCase())) {
            throw new BusinessException("仅支持上传格式: " + props.getUpload().getAllowedExtensions());
        }
        if (file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        if (file.getSize() > props.getUpload().getMaxSizeMb() * 1024L * 1024) {
            throw new BusinessException("文件大小超出限制（最大 " + props.getUpload().getMaxSizeMb() + "MB）");
        }

        // 分块参数：上传接口可覆盖全局默认，做边界校验
        int chunkSize = chunkSizeParam != null ? chunkSizeParam : props.getChunk().getSize();
        int chunkOverlap = chunkOverlapParam != null ? chunkOverlapParam : props.getChunk().getOverlap();
        if (chunkSize < 100 || chunkSize > 2000) {
            throw new BusinessException("chunkSize 需在 100~2000 之间");
        }
        if (chunkOverlap < 0 || chunkOverlap > chunkSize / 2) {
            throw new BusinessException("chunkOverlap 需在 0~chunkSize/2 之间");
        }

        // 先落一条 PARSING 状态记录，作为状态机起点
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(ext.toLowerCase());
        doc.setFileSize(file.getSize());
        doc.setStatus(Document.STATUS_PARSING);
        doc.setCreatedBy(user.id());
        documentMapper.insert(doc);

        // 在请求线程内读完字节流：MultipartFile 的临时文件在请求结束后可能被清理，
        // 异步模式下后台线程必须拿字节数组而不是 MultipartFile
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            doc.setStatus(Document.STATUS_FAILED);
            doc.setErrorMsg("文件读取失败: " + e.getMessage());
            documentMapper.updateById(doc);
            throw new BusinessException(500, "文件读取失败: " + e.getMessage());
        }

        if (Boolean.TRUE.equals(props.getUpload().getAsync())) {
            // 异步模式：秒返回 PARSING 状态，后台线程处理，轮询文档列表直到 READY/FAILED
            uploadExecutor.submit(() -> processDocument(doc, data, fileName, ext, chunkSize, chunkOverlap));
        } else {
            // 同步模式：处理完再返回（演示/评测友好）
            processDocument(doc, data, fileName, ext, chunkSize, chunkOverlap);
        }
        return DocumentVO.from(doc);
    }

    /**
     * 文档处理主流程（同步/异步共用）：
     * 解析 → 分块 → 批量向量化 → 入库 → 更新状态 → 重建 BM25 索引；
     * 失败补偿：清理已入库片段 + 标记 FAILED（双数据源无事务，靠状态机收敛）
     */
    private void processDocument(Document doc, byte[] data, String fileName, String ext,
                                 int chunkSize, int chunkOverlap) {
        try {
            // 【RAG-1】文档解析：Tika 自动识别 PDF/Word，提取纯文本
            String text = parseText(data, fileName);

            // 【RAG-2】结构化分块：标题感知 + 两级（small-to-big），
            // 每块带章节路径（heading_path）与父块序号（parent_index），父块文本随子块入库
            int parentSize = props.getChunk().getParentSize();
            List<ChunkingService.StructuredChunk> chunks =
                    chunkingService.chunkStructured(text, chunkSize, chunkOverlap, parentSize);
            if (chunks.isEmpty()) {
                throw new BusinessException("文档未解析出有效文本内容");
            }
            boolean withParents = chunks.get(0).parentIndex() > 0;

            // 【RAG-2.5】父块摘要（RAPTOR 简化版摘要树的上半场）：
            // LLM 为每个父块生成一句话摘要，失败的单块跳过（检索侧自动降级）
            Map<Integer, String> summaryByIndex = new LinkedHashMap<>();
            if (withParents) {
                Map<Integer, String> parentByIndex = new LinkedHashMap<>();
                chunks.forEach(c -> parentByIndex.putIfAbsent(c.parentIndex(), c.parent()));
                List<Integer> parentIdxList = new ArrayList<>(parentByIndex.keySet());
                List<String> summaries = summaryService.summarize(
                        parentIdxList.stream().map(parentByIndex::get).toList());
                for (int i = 0; i < parentIdxList.size(); i++) {
                    if (summaries.get(i) != null) {
                        summaryByIndex.put(parentIdxList.get(i), summaries.get(i));
                    }
                }
            }

            // 【RAG-3】统一批量向量化：子块文本 + 摘要文本 一次任务队列分批调用 BGE-M3，
            // 减少模型 API 往返次数（batch-size 防止单次请求过大触发限流）
            List<String> childTexts = chunks.stream().map(ChunkingService.StructuredChunk::child).toList();
            List<String> summaryTexts = new ArrayList<>(summaryByIndex.values());
            List<String> allTexts = new ArrayList<>(childTexts);
            allTexts.addAll(summaryTexts);
            List<float[]> allVectors = new ArrayList<>(allTexts.size());
            int batchSize = props.getEmbedding().getBatchSize();
            for (int start = 0; start < allTexts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, allTexts.size());
                allVectors.addAll(embeddingService.embedBatch(allTexts.subList(start, end)));
                log.info("文档[{}]向量化进度: {}/{} 文本", doc.getId(), end, allTexts.size());
            }
            List<float[]> childVectors = allVectors.subList(0, childTexts.size());
            List<float[]> summaryVectors = allVectors.subList(childTexts.size(), allVectors.size());

            // 【RAG-4】入库：子块（原文+向量+元数据）写入 pgvector
            List<ChunkRecord> records = new ArrayList<>(chunks.size());
            for (int j = 0; j < chunks.size(); j++) {
                ChunkingService.StructuredChunk c = chunks.get(j);
                // chunk_index 从 1 开始，溯源时展示"第x段"
                records.add(new ChunkRecord(doc.getKbId(), doc.getId(), j + 1, c.child(),
                        childVectors.get(j), c.parent(), c.headingPath(),
                        withParents ? c.parentIndex() : null,
                        withParents ? summaryByIndex.get(c.parentIndex()) : null));
            }
            vectorStoreDao.insertBatch(records);

            // 摘要行入库 chunk_summary（向量已随统一批次生成）
            if (!summaryVectors.isEmpty()) {
                List<SummaryRecord> summaryRecords = new ArrayList<>(summaryVectors.size());
                int k = 0;
                for (Map.Entry<Integer, String> e : summaryByIndex.entrySet()) {
                    summaryRecords.add(new SummaryRecord(doc.getKbId(), doc.getId(),
                            e.getKey(), e.getValue(), summaryVectors.get(k++)));
                }
                summaryDao.insertBatch(summaryRecords);
            }

            doc.setChunkCount(chunks.size());
            doc.setStatus(Document.STATUS_READY);
            documentMapper.updateById(doc);

            // 新片段加入后，重建该知识库 BM25 索引
            bm25IndexService.rebuild(doc.getKbId());
        } catch (Exception e) {
            // 失败补偿：清掉可能已入库的片段与摘要，文档标记 FAILED，保证不产生脏向量
            vectorStoreDao.deleteByDocId(doc.getId());
            summaryDao.deleteByDocId(doc.getId());
            doc.setStatus(Document.STATUS_FAILED);
            doc.setErrorMsg(truncate(e.getMessage(), 500));
            documentMapper.updateById(doc);
            if (Boolean.TRUE.equals(props.getUpload().getAsync())) {
                log.error("文档[{}]处理失败: {}", doc.getId(), e);
            } else {
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException(500, "文档处理失败: " + e.getMessage());
            }
        }
    }

    public PageVO<DocumentVO> page(Long kbId, long page, long size) {
        knowledgeBaseService.requireAccess(kbId);
        Page<Document> p = documentMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getKbId, kbId)
                        .orderByDesc(Document::getCreatedAt));
        Page<DocumentVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(DocumentVO::from).toList());
        return PageVO.of(voPage);
    }

    public void delete(Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        knowledgeBaseService.requireAccess(doc.getKbId());
        // 先删向量片段与摘要再删元数据，随后重建 BM25 索引
        vectorStoreDao.deleteByDocId(docId);
        summaryDao.deleteByDocId(docId);
        documentMapper.deleteById(docId);
        bm25IndexService.rebuild(doc.getKbId());
    }

    /**
     * 【RAG-1】Tika 解析：不落盘直接从字节流抽取文本。
     * writeLimit 限制写入上限，防止超大文档打爆内存；
     * 扫描件 PDF 无文本层（需 OCR），属于明确不支持的范围
     */
    private String parseText(byte[] data, String fileName) {
        BodyContentHandler handler = new BodyContentHandler(props.getUpload().getTikaWriteLimit());
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (SAXException | TikaException e) {
            throw new BusinessException(400, "文档解析失败（文件可能损坏或内容过大）: " + e.getMessage());
        } catch (IOException e) {
            throw new BusinessException(500, "文件读取失败: " + e.getMessage());
        }
        String text = handler.toString().trim();
        if (text.isEmpty()) {
            throw new BusinessException(400, "未解析到文本内容（扫描件 PDF 需 OCR，暂不支持）");
        }
        return text;
    }

    private String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
