package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.ConversationMapper;
import com.enterprise.rag.dao.mapper.QaLogMapper;
import com.enterprise.rag.entity.Conversation;
import com.enterprise.rag.entity.QaLog;
import com.enterprise.rag.entity.vo.AskResponse;
import com.enterprise.rag.entity.vo.SearchResponse;
import com.enterprise.rag.entity.vo.SourceVO;
import com.enterprise.rag.util.SecurityUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

/**
 * RAG 问答编排：检索 → 幻觉兜底 → Prompt 组装 → LLM 调用 → 溯源返回 → 审计落库
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final QaLogService qaLogService;
    private final ConversationMapper conversationMapper;
    private final QaLogMapper qaLogMapper;
    private final RateLimitService rateLimitService;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final RagProperties props;

    /** 兜底固定话术：命中兜底时直接返回，不经过 LLM */
    private static final String FALLBACK_ANSWER = "没有找到相关资料，请换个问法或先上传相关文档。";
    /** 多轮对话注入的历史轮数 */
    private static final int HISTORY_ROUNDS = 3;

    public AskResponse ask(Long kbId, String question, Long conversationId) {
        long start = System.currentTimeMillis();
        // 知识库隔离校验
        knowledgeBaseService.requireAccess(kbId);
        // 接口限流：/ask 每请求消耗 2~3 次 LLM 调用，防 key 被刷烧钱
        rateLimitService.checkAsk(SecurityUtil.currentUser().id());

        // 【问答-0】多轮对话：解析会话（新会话建档；已存在会话校验归属并取最近几轮历史）
        Long convId = conversationId;
        String history = "";
        if (convId != null) {
            Conversation conv = conversationMapper.selectById(convId);
            if (conv == null || !conv.getUserId().equals(SecurityUtil.currentUser().id())
                    || !conv.getKbId().equals(kbId)) {
                throw new BusinessException(404, "会话不存在或不属于当前知识库");
            }
            history = buildHistory(convId);
        } else {
            Conversation conv = new Conversation();
            conv.setKbId(kbId);
            conv.setUserId(SecurityUtil.currentUser().id());
            conversationMapper.insert(conv);
            convId = conv.getId();
        }

        // 【问答-1】混合检索（向量 + BM25，RRF 融合）
        RetrievalResult retrieval = retrievalService.retrieve(kbId, question);

        boolean fallback;
        String answer;
        List<SourceVO> sources;
        String context;

        // 【问答-2】幻觉兜底：检索质量不达标（无召回 / 最佳向量相似度低于阈值）
        // 直接返回固定话术、不调 LLM —— 从根上禁止模型编造
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            log.info("知识库[{}]检索质量不达标(空召回={}, 最佳相似度={})，触发幻觉兜底",
                    kbId, retrieval.isEmpty(), retrieval.maxVectorSimilarity());
            fallback = true;
            answer = FALLBACK_ANSWER;
            sources = List.of();
            context = "";
        } else {
            // 【问答-3】Prompt 组装：检索片段 + 对话历史注入模板
            context = buildContext(retrieval.chunks());
            String systemPrompt = props.getPromptTemplate()
                    .replace("{context}", context)
                    .replace("{history}", history.isBlank() ? "（无）" : history)
                    .replace("{question}", question);

            // 【问答-4】LLM 调用（低温度 0.1 + Prompt 内强约束"不得编造"）
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(systemPrompt), UserMessage.from(question))
                    .build());
            answer = response.aiMessage().text();

            // 溯源：返回引用来源片段（含章节路径与命中词）
            sources = retrieval.chunks().stream().map(this::toSource).toList();
            // 二次兜底：模型仍输出"找不到"话术时同样标记（审计用）
            fallback = answer.contains("没有找到相关资料");
        }

        // 【问答-5】审计落库：提问/回答/检索上下文/来源/耗时全量记录（挂到会话下）
        long latency = System.currentTimeMillis() - start;
        qaLogService.save(kbId, convId, question, answer, sources, context, fallback, latency);
        return new AskResponse(answer, fallback, sources, convId);
    }

    /** 最近几轮问答历史拼接（时间序，每条截断防过长） */
    private String buildHistory(Long conversationId) {
        List<QaLog> recent = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .eq(QaLog::getConversationId, conversationId)
                .orderByDesc(QaLog::getCreatedAt)
                .last("LIMIT " + HISTORY_ROUNDS * 2));
        StringBuilder sb = new StringBuilder();
        for (int i = recent.size() - 1; i >= 0; i--) {
            QaLog item = recent.get(i);
            sb.append("问：").append(truncateText(item.getQuestion(), 200)).append('\n');
            sb.append("答：").append(truncateText(item.getAnswer(), 300)).append('\n');
        }
        return sb.toString();
    }

    private String truncateText(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    /**
     * 流式问答（SSE）：复用同一检索链路，答案逐 token 推送。
     * 事件：message(内容片段) → sources(溯源 JSON) → 连接关闭；
     * 兜底场景推送一条 message + 空 sources，不调 LLM。
     * 流式接口无法走统一 Result 包装，异常通过 error 事件返回。
     */
    public void askStream(Long kbId, String question, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        knowledgeBaseService.requireAccess(kbId);
        RetrievalResult retrieval = retrievalService.retrieve(kbId, question);

        // 幻觉兜底：与同步接口同一判定逻辑
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            sendEvent(emitter, "message", FALLBACK_ANSWER);
            sendEvent(emitter, "sources", "[]");
            qaLogService.save(kbId, null, question, FALLBACK_ANSWER, List.of(), "",
                    true, System.currentTimeMillis() - start);
            emitter.complete();
            return;
        }

        String context = buildContext(retrieval.chunks());
        String systemPrompt = props.getPromptTemplate()
                .replace("{context}", context)
                .replace("{question}", question);
        List<SourceVO> sources = retrieval.chunks().stream().map(this::toSource).toList();

        streamingChatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(question))
                .build(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                sendEvent(emitter, "message", partial);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                String answer = response.aiMessage().text();
                try {
                    sendEvent(emitter, "sources", objectMapper.writeValueAsString(sources));
                    qaLogService.save(kbId, null, question, answer, sources, context,
                            answer.contains("没有找到相关资料"), System.currentTimeMillis() - start);
                } catch (JsonProcessingException e) {
                    log.error("溯源信息序列化失败", e);
                } finally {
                    emitter.complete();
                }
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式回答失败", error);
                sendEvent(emitter, "error", "回答生成失败: " + error.getMessage());
                qaLogService.save(kbId, null, question, "", List.of(), context,
                        false, System.currentTimeMillis() - start);
                emitter.complete();
            }
        });
        emitter.onTimeout(emitter::complete);
    }

    /** SSE 推送：客户端提前断开（IOException）时静默忽略 */
    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            log.warn("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
        }
    }

    /** 仅检索（不调 LLM）：检索调试与评测脚本使用，不落问答日志 */
    public SearchResponse search(Long kbId, String question) {
        knowledgeBaseService.requireAccess(kbId);
        rateLimitService.checkSearch(SecurityUtil.currentUser().id());
        RetrievalResult retrieval = retrievalService.retrieve(kbId, question);
        List<SourceVO> sources = retrieval.chunks().stream().map(this::toSource).toList();
        return new SearchResponse(retrieval.maxVectorSimilarity(), sources);
    }

    private SourceVO toSource(RetrievedChunk c) {
        return new SourceVO(c.getDocId(), c.getFileName(), c.getChunkIndex(),
                c.getContent(), c.getScore(), c.getHeadingPath(), c.getMatchedTerms());
    }

    /** 上下文拼接：给来源片段编号，方便模型在回答中引用【来源n】 */
    private String buildContext(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[来源").append(i + 1).append("]《").append(c.getFileName())
                    .append("》第").append(c.getChunkIndex()).append("段：\n")
                    .append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }
}
