package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统一问答的知识库路由：LLM 从当前用户可见的知识库中选最相关的前 N 个。
 * 只读库列表、不针对某个 kb 操作，因此不做单库权限校验。
 * <p>
 * 不变量 6：LLM 异常 / 解析无有效 id / 输出「无明显匹配」一律降级为全部候选库 —— 宁可多搜，不能漏搜。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeRouterService {

    private final ChatModel chatModel;
    private final RagProperties props;

    /** 注入 Prompt 的历史长度上限（帮助"那它呢"类指代问题路由） */
    private static final int HISTORY_MAX_CHARS = 400;

    /** 数字串：用于从路由输出行里抠知识库 id */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static final String ROUTE_PROMPT = """
            你是企业知识库检索路由助手。用户提问时不会指定知识库，请你判断该问题最可能涉及哪几个知识库。

            候选知识库：
            %s

            输出要求：
            1. 最多选择 %d 个最相关的知识库，每行一个，格式为：知识库id|理由，理由不超过 20 字；
            2. 按相关度从高到低排序，不要编号、不要解释、不要输出候选之外的 id；
            3. 如果问题与所有候选知识库都无关，只输出一行：0|无明显匹配。
            示例：
            2|考勤与休假制度
            1|员工手册相关条款
            """;

    private static final String QUESTION_TEMPLATE = """
            对话历史：
            %s
            用户问题：%s
            """;

    /** 路由选库：返回参与检索的库 id（顺序即相关度）
     *  把候选知识库清单和用户问题交给 LLM，让它挑出最相关的 ≤topN 个库，返回库 id
     *  任一环节失败降级为全部候选库
     */
    public List<Long> route(String question, String history, List<KnowledgeBaseVO> candidates) {
        // 没有候选库直接返回空
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 唯一候选直通：省一次 LLM 调用（结果一样，没必要花钱）
        if (candidates.size() == 1) {
            return List.of(candidates.get(0).getId());
        }
        // 配置 rag.routing.enabled=false 时关闭路由，直接返回全部候选 id
        if (!Boolean.TRUE.equals(props.getRouting().getEnabled())) {
            return allIds(candidates);
        }
        int topN = props.getRouting().getTopN();
        int maxCandidates = props.getRouting().getMaxCandidates();
        // 候选超上限就截前 maxCandidates 个
        List<KnowledgeBaseVO> limited = candidates.size() > maxCandidates
                ? candidates.subList(0, maxCandidates) : candidates;   // 候选已按创建时间倒序
        try {
            // 把候选拼成每行一个的清单文本给模型看
            String candidateText = limited.stream()
                    .map(c -> "- id=%d 名称=%s 描述=%s".formatted(c.getId(), c.getName(),
                            StringUtils.hasText(c.getDescription()) ? c.getDescription() : "（无描述）"))
                    .collect(Collectors.joining("\n"));
            // response 里装的就是：调用 LLM 大模型后返回的内容——主体是模型"选中的路由知识库 + 每个库的理由"，一行一个，格式 知识库id|理由
            var response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(ROUTE_PROMPT.formatted(candidateText, topN)),
                            UserMessage.from(QUESTION_TEMPLATE.formatted(truncateHistory(history), question)))
                    .build());
            // 把 limited（实际给 LLM 的那批候选库）转成流  每个元素取 getId()  存到 validIds 变量 → 作用：当白名单，下一行解析模型输出时，只有出现在这个集合里的 id 才认可
            Set<Long> validIds = limited.stream().map(KnowledgeBaseVO::getId).collect(Collectors.toSet());
            // picked 里记录的就是统一问答（自动选库）进行路由访问的知识库 id
            List<Long> picked = parseRoutingOutput(response.aiMessage().text(), validIds, topN);
            if (picked.isEmpty()) {
                log.info("路由未匹配到知识库，降级检索全部候选库");
                return allIds(candidates);
            }
            log.info("路由命中知识库 {}", picked);
            return picked;
        } catch (Exception e) {
            log.warn("知识库路由失败，降级检索全部候选库: {}", e.getMessage());
            return allIds(candidates);
        }
    }

    /**
     * 解析路由输出（沿用项目「按行纯文本」风格，不引入 JSON 解析）：
     * 每行按 | 切成 id 和理由（兼容全角竖线）；抠不出数字、id=0（无明显匹配）、
     * 不在候选集内（模型幻觉）、重复的行都剔除；最多取 topN 个
     */
    // 模型原始输出 raw、合法 id 白名单 validIds、最多取几个 topN；返回解析出的 id 列表
    static List<Long> parseRoutingOutput(String raw, Set<Long> validIds, int topN) {
        // 模型没给文本（null）直接返回空列表
        if (raw == null) {
            return List.of();
        }
        // 建结果列表——用 List 是为了保持加入顺序（= 模型给的相关度顺序）
        List<Long> picked = new ArrayList<>();
        // strip() 去首尾空白；split("\\R") 按任意换行（\n/\r\n/\r）切成行，逐行处理
        for (String line : raw.strip().split("\\R")) {
            // 每行按竖线切两段：半角 | 和全角 ｜ 都认，限制切 2 段
            String[] parts = line.split("[|｜]", 2);
            // 从 id 段里抠数字
            Long id = parseId(parts[0]);
            // 四道过滤，满足任一就跳过这一行文本：① 抠不出 id；② id=0（模型表示「无明显匹配」）；③ 不在白名单（模型编的库）；④ 这行 id 已经收过（去重）
            if (id == null || id == 0 || !validIds.contains(id) || picked.contains(id)) {
                continue;
            }
            // 添加进结果列表中
            picked.add(id);
            // 收满 topN 个就停
            if (picked.size() >= topN) {
                break;
            }
        }
        return picked;
    }

    /** 从「2」「id=2」「知识库2」之类输出里抠出数字 id：取最后一个数字串，兼容行首编号（如「1. 2」中的 2）；抠不出或过长返回 null */
    private static Long parseId(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = DIGITS.matcher(text);
        String digits = null;
        while (matcher.find()) {
            digits = matcher.group();
        }
        if (digits == null || digits.length() > 9) {
            return null;
        }
        return Long.parseLong(digits);
    }

    /** 取历史末尾 N 字符：多轮追问的指代词在最近几轮，同时控制 Prompt 长度 */
    private static String truncateHistory(String history) {
        if (history == null || history.isBlank()) {
            return "（无）";
        }
        return history.length() <= HISTORY_MAX_CHARS ? history
                : history.substring(history.length() - HISTORY_MAX_CHARS);
    }

    private static List<Long> allIds(List<KnowledgeBaseVO> candidates) {
        return candidates.stream().map(KnowledgeBaseVO::getId).toList();
    }
}
