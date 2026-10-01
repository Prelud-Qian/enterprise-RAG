package com.enterprise.rag.service;

import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.entity.vo.ClassifyVO;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 上传前文档自动分类：Tika 取样文档开头 → LLM 从当前用户可见的知识库中选一个。
 * <p>
 * 不变量 6：LLM 失败只降级为「不推荐」，接口本身不 500 —— 分类是上传的前置便利步骤，
 * 不能因为它挂了就让人传不了文档。只有文件本身非法（扩展名/空/超限）才抛 400，
 * 规则读的是 rag.upload 同一份配置，与上传接口保持一致。
 * <p>
 * 本接口不针对某个 kb 操作，只读当前用户可见的库列表，因此不走 requireAccess。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentClassifyService {

    private final ChatModel chatModel;
    private final KnowledgeBaseService knowledgeBaseService;
    private final RagProperties props;

    /** 推荐理由最大长度（防止模型啰嗦） */
    private static final int REASON_MAX = 60;

    private static final String CLASSIFY_PROMPT = """
            你是企业知识库文档归类助手。请根据用户提供的文档内容，判断它最应该归入哪个知识库。

            候选知识库：
            %s

            输出要求：
            1. 只输出一行，格式为：知识库id|推荐理由，推荐理由不超过 30 字，不要换行、不要编号、不要解释；
            2. 必须从上面候选知识库的 id 中选择一个；如果都不匹配，输出：0|无明显匹配。
            示例：2|内容为考勤与休假规定，属于人事制度
            """;

    private static final String DOC_TEMPLATE = """
            文档名：%s
            文档开头：
            %s
            """;

    /** 上传前自动分类：返回推荐结果，失败/无库/单库都走降级（recommendedKbId 为 null 或唯一候选） */
    public ClassifyVO classify(MultipartFile file) {
        String fileName = validate(file);

        List<KnowledgeBaseVO> candidates = knowledgeBaseService.listMine();
        if (candidates.isEmpty()) {
            return ClassifyVO.degraded("你还没有知识库，请先创建知识库再上传文档");
        }
        if (!Boolean.TRUE.equals(props.getClassify().getEnabled())) {
            return ClassifyVO.degraded("自动分类已关闭，请手动选择知识库");
        }
        // 唯一候选直通：省一次 LLM 调用（结果一样，没必要花钱）
        if (candidates.size() == 1) {
            KnowledgeBaseVO only = candidates.get(0);
            return new ClassifyVO(only.getId(), only.getName(), "当前只有一个知识库，默认选择它", false);
        }
        int maxCandidates = props.getClassify().getMaxCandidates();
        if (candidates.size() > maxCandidates) {
            candidates = candidates.subList(0, maxCandidates);   // listMine 已按创建时间倒序
        }

        String sample = sampleText(file, fileName);
        if (sample.isEmpty()) {
            return ClassifyVO.degraded("未能解析出文档内容（扫描件 PDF 需 OCR），请手动选择知识库");
        }

        Map<Long, KnowledgeBaseVO> byId = candidates.stream()
                .collect(Collectors.toMap(KnowledgeBaseVO::getId, Function.identity()));
        try {
            String candidateText = candidates.stream()
                    .map(c -> "- id=%d 名称=%s 描述=%s".formatted(c.getId(), c.getName(),
                            StringUtils.hasText(c.getDescription()) ? c.getDescription() : "（无描述）"))
                    .collect(Collectors.joining("\n"));
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(CLASSIFY_PROMPT.formatted(candidateText)),
                            UserMessage.from(DOC_TEMPLATE.formatted(fileName, sample)))
                    .build());
            return parseRecommendation(response.aiMessage().text(), byId);
        } catch (Exception e) {
            log.warn("文档自动分类失败，降级为手动选择: {}", e.getMessage());
            return ClassifyVO.degraded("自动分类服务暂不可用，请手动选择知识库");
        }
    }

    /**
     * 解析 LLM 输出（沿用项目「按行纯文本」风格，不引入 JSON 解析）：
     * 取第一行按 | 切成 id 和理由；id 抠不出数字、或不在候选集内（模型幻觉）都视为未识别 → 降级
     */
    private ClassifyVO parseRecommendation(String raw, Map<Long, KnowledgeBaseVO> byId) {
        String firstLine = raw == null ? "" : raw.strip().lines().findFirst().orElse("").strip();
        String[] parts = firstLine.split("[|｜]", 2);   // 兼容模型输出的全角竖线
        Long picked = parseId(parts[0]);
        KnowledgeBaseVO hit = picked == null ? null : byId.get(picked);
        if (hit == null) {
            log.warn("自动分类未识别出候选知识库，模型输出: {}", firstLine);
            return ClassifyVO.degraded("未能识别合适的知识库，请手动选择");
        }
        String reason = parts.length > 1 ? truncate(parts[1].strip()) : "AI 根据文档内容推荐";
        return new ClassifyVO(hit.getId(), hit.getName(), reason, true);
    }

    /** 从「2」「id=2」「知识库2」之类输出里抠出数字 id，抠不出或过长返回 null */
    private Long parseId(String text) {
        String digits = text == null ? "" : text.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /**
     * 分类取样：Tika 只读文档开头 N 个字符。
     * BodyContentHandler 到达 writeLimit 时会抛异常中断解析 —— 这正是「只取开头」想要的效果，
     * 因此 catch 住、只要已写入的文本非空就算取样成功（比解析全文快得多）。
     * 与 DocumentService.parseText 的区别：那里解析中断 = 文档损坏（抛 400），这里解析中断是预期路径，
     * 异常语义相反，所以没有合并成一个方法。
     */
    private String sampleText(MultipartFile file, String fileName) {
        int limit = props.getClassify().getSampleChars();
        BodyContentHandler handler = new BodyContentHandler(limit);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (InputStream in = file.getInputStream()) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            // 取满上限的正常中断（SAXException）与个别解析器的包装异常统一吞掉：
            // 判定标准是「handler 里有没有文本」，不是有没有抛异常
            log.debug("分类取样提前结束（可能已取满 {} 字符）: {}", limit, e.getMessage());
        }
        return handler.toString().trim();
    }

    /** 文件校验：规则与上传接口同源（都读 rag.upload），保证「分类能过、上传被拒」不会发生 */
    private String validate(MultipartFile file) {
        RagProperties.Upload cfg = props.getUpload();
        String original = file.getOriginalFilename();
        if (!StringUtils.hasText(original)) {
            throw new BusinessException("文件名不能为空");
        }
        String fileName = StringUtils.cleanPath(original);
        String ext = StringUtils.getFilenameExtension(fileName);
        if (ext == null || !cfg.getAllowedExtensions().contains(ext.toLowerCase())) {
            throw new BusinessException("仅支持上传格式: " + cfg.getAllowedExtensions());
        }
        if (file.isEmpty()) {
            throw new BusinessException("上传文件为空");
        }
        if (file.getSize() > cfg.getMaxSizeMb() * 1024L * 1024) {
            throw new BusinessException("文件大小超出限制（最大 " + cfg.getMaxSizeMb() + "MB）");
        }
        return fileName;
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() <= REASON_MAX ? s : s.substring(0, REASON_MAX));
    }
}
