package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.ConversationMapper;
import com.enterprise.rag.dao.mapper.QaLogMapper;
import com.enterprise.rag.entity.Conversation;
import com.enterprise.rag.entity.QaLog;
import com.enterprise.rag.entity.vo.AskResponse;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 问答编排：检索 → 幻觉兜底 → Prompt 组装 → LLM 调用 → 溯源返回 → 审计落库
 */
@Service
@RequiredArgsConstructor
@Slf4j
/**
 * QaService 的作用是：接收用户的问题，去知识库里找相关资料，把资料和问题组装成提示词（Prompt），
 * 调用大模型（LLM）生成回答，并把这次问答记录存进数据库。
 */
public class QaService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final KnowledgeRouterService knowledgeRouterService;
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
        // 记开始时间，最后算耗时
        long start = System.currentTimeMillis();
        // 知识库隔离校验
        knowledgeBaseService.requireAccess(kbId);
        // 接口限流：/ask 每请求消耗 2~3 次 LLM 调用，防 key 被刷烧钱
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);

        // 【问答-0】多轮对话：解析会话（新会话建档；已存在会话校验归属并取最近几轮历史）
        ConversationContext convCtx = resolveConversation(kbId, userId, conversationId);
        return doAsk(question, convCtx, List.of(kbId), start, userId);
    }

    /** 统一问答（跨库路由）：不指定知识库，由 KnowledgeRouterService 从当前用户可见库中选 */
    public AskResponse ask(String question, Long conversationId) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        ConversationContext convCtx = resolveConversationForUser(userId, conversationId);
        // 路由放在检索之前：候选为空会抛 400，此刻还没开始产出响应
        List<Long> kbIds = route(question, convCtx.history());
        return doAsk(question, convCtx, kbIds, start, userId);
    }

    /** 公共尾段：检索 → 兜底 → Prompt → LLM → 溯源 → 审计落库；单库问记该库 id，跨库记 NULL */
    private AskResponse doAsk(String question, ConversationContext convCtx, List<Long> kbIds,
                              long start, Long userId) {
        Long convId = convCtx.convId();
        String history = convCtx.history();
        Long logKbId = kbIds.size() == 1 ? kbIds.get(0) : null;

        // 【问答-1】混合检索（向量 + BM25，RRF 融合）
        RetrievalResult retrieval = retrievalService.retrieve(kbIds, question);

        boolean fallback;
        String answer;
        List<SourceVO> sources;
        String context;

        // 【问答-2】幻觉兜底：检索出来的资料质量不达标（无召回 / 最佳向量相似度低于阈值）
        // 直接返回固定话术、不调 LLM —— 从根上禁止模型编造
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            log.info("知识库{}检索质量不达标(空召回={}, 最佳相似度={})，触发幻觉兜底",
                    kbIds, retrieval.isEmpty(), retrieval.maxVectorSimilarity());
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
        qaLogService.save(userId, logKbId, convId, question, answer, sources, context, fallback, latency);
        return new AskResponse(answer, fallback, sources, convId);
    }

    /** 会话解析结果：会话 id + 注入 Prompt 的历史文本 */
    private record ConversationContext(Long convId, String history) {
    }

    /**
     * 【问答-0】会话解析（ask / askStream 共用）：新会话建档；已存在会话校验归属并取最近几轮历史。
     * userId 由调用方显式传入，不在这里取 SecurityUtil —— SSE 回调跑在 langchain4j 线程池上，那里读不到 SecurityContext
     */
    private ConversationContext resolveConversation(Long kbId, Long userId, Long conversationId) {
        /**
         * 查会话，是为了确认身份、绑定知识库、维护聊天列表；
         * 查最近问答，是为了给大模型注入“短期记忆”，让它能听懂“那”、“它”、“这个”等指代词，实现连贯的多轮对话。
         */
        if (conversationId != null) {   // 传了会话 id
            Conversation conv = conversationMapper.selectById(conversationId);  // 查会话
            /**
             * 如果满足以下任意一种情况，就认为这个请求是不合法的
             *
             * 根据传入的 conversationId 没有查找到对应的会话记录
             * 查出来的这个会话，它的归属用户 ID 不等于 当前发起请求的用户 ID
             * 这个会话原本绑定的知识库 ID 不等于 当前请求想要查询的知识库 ID
             * 这个会话是统一问答会话（kb_id 为 NULL，没有库维度），不能用在按库接口里
             */
            if (conv == null || !conv.getUserId().equals(userId)
                    || conv.getKbId() == null || !conv.getKbId().equals(kbId)) {
                throw new BusinessException(404, "会话不存在或不属于当前知识库");
            }
            // 查最近几轮问答拼成文本
            return new ConversationContext(conversationId, buildHistory(conversationId));
        }
        // 没传会话 id
        Conversation conv = new Conversation(); // 新建会话对象
        conv.setKbId(kbId);
        conv.setUserId(userId);
        conversationMapper.insert(conv);    // 插入数据库
        return new ConversationContext(conv.getId(), "");
    }

    /** 统一问答的会话解析：只校验归属用户（kb_id 为 NULL，没有库维度） */
    private ConversationContext resolveConversationForUser(Long userId, Long conversationId) {
        if (conversationId != null) {
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv == null || !conv.getUserId().equals(userId)) {
                throw new BusinessException(404, "会话不存在或不属于当前用户");
            }
            return new ConversationContext(conversationId, buildHistory(conversationId));
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);   // 统一问答：不绑定知识库，kb_id 留 NULL
        conversationMapper.insert(conv);
        return new ConversationContext(conv.getId(), "");
    }

    /** 路由选库：候选来自 listMine（天然白名单，客户端无法注入 kbId）；一个库都没有时直接报错 */
    private List<Long> route(String question, String history) {
        List<KnowledgeBaseVO> candidates = knowledgeBaseService.listMine();
        if (candidates.isEmpty()) {
            throw new BusinessException(400, "尚未创建知识库，请先在「资料管理」中创建并上传文档");
        }
        return knowledgeRouterService.route(question, history, candidates);
    }

    /** 最近几轮问答历史拼接（时间序，每条截断防过长） */
    /**
     * 去数据库里捞出这个会话最近的几轮问答记录，把它们拼成一段纯文本，作为“历史记忆”喂给大模型。
     */
    private String buildHistory(Long conversationId) {
        List<QaLog> recent = qaLogMapper.selectList(new LambdaQueryWrapper<QaLog>()
                .eq(QaLog::getConversationId, conversationId) // 只查这个会话的记录
                .orderByDesc(QaLog::getCreatedAt)   // 按时间倒序（最新的在前）
                .last("LIMIT " + HISTORY_ROUNDS * 2)); // 只取最近 N 条
        StringBuilder sb = new StringBuilder();
        for (int i = recent.size() - 1; i >= 0; i--) {
            QaLog item = recent.get(i);
            sb.append("问：").append(truncateText(item.getQuestion(), 200)).append('\n');
            sb.append("答：").append(truncateText(item.getAnswer(), 300)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 字符串截断：太长就切掉超出部分
     */
    private String truncateText(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max));
    }

    /**
     * 流式问答（SSE）：复用同一检索链路，答案逐 token 推送。
     * 事件：meta(会话 id) → message(内容片段) → sources(溯源 JSON) → 连接关闭；
     * 兜底场景推送一条 message + 空 sources，不调 LLM。
     * 流式接口无法走统一 Result 包装，异常通过 error 事件返回。
     */

    // 和 ask 走同一条链路（检索 → 兜底 → 组装 prompt → 调 LLM → 溯源 → 审计），区别只有一个：答案边生成边推送，不等全部生成完。
    public void askStream(Long kbId, String question, Long conversationId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        knowledgeBaseService.requireAccess(kbId);
        // 限流与 userId 都必须在请求线程取：SSE 回调跑在 langchain4j 的线程池上，那里读不到 SecurityContext
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        // 会话解析同样在请求线程做（新会话要写库）。此刻 emitter 还没 initialize，
        // 抛出的 404 照走 GlobalExceptionHandler 返回 JSON —— 和 requireAccess/checkAsk 是同一条通道
        ConversationContext conv = resolveConversation(kbId, userId, conversationId);
        // 检索也放在首个 SSE 事件之前：embedding/DB 异常时响应尚未提交，照走同一条 JSON 错误通道
        RetrievalResult retrieval = retrievalService.retrieve(List.of(kbId), question);
        // 首个事件回传会话 id：首次提问时前端还不知道它，拿不到就无法追问
        sendJson(emitter, "meta", Map.of("conversationId", conv.convId()));
        doAskStream(emitter, question, conv, retrieval, List.of(kbId), start, userId);
    }

    /** 统一流式问答（跨库路由）：事件序同旧接口；路由与检索都在 meta 之前完成，出错仍走 JSON 通道 */
    public void askStream(String question, Long conversationId, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtil.currentUser().id();
        rateLimitService.checkAsk(userId);
        ConversationContext conv = resolveConversationForUser(userId, conversationId);
        // 路由必须在第一个 SSE 事件之前：候选为空抛 400 时响应还没开始，才能返回 JSON
        List<Long> kbIds = route(question, conv.history());
        // 检索同理：失败时响应尚未提交，照走 JSON 错误通道
        RetrievalResult retrieval = retrievalService.retrieve(kbIds, question);
        // 首个事件回传会话 id：首次提问时前端还不知道它，拿不到就无法追问
        sendJson(emitter, "meta", Map.of("conversationId", conv.convId()));
        doAskStream(emitter, question, conv, retrieval, kbIds, start, userId);
    }

    /** 流式任务上下文：onError 自动重试时要原样重建一次调用，用 record 打包避免长参数列表 */
    private record StreamJob(SseEmitter emitter, String question, String systemPrompt, List<SourceVO> sources,
                             String context, Long convId, Long logKbId, long start, Long userId,
                             AtomicBoolean gotToken, AtomicBoolean retried) {
    }

    /** 公共尾段：兜底 → Prompt → 发起流式调用（与 doAsk 同判定逻辑；检索由调用方在首个 SSE 事件之前完成） */
    private void doAskStream(SseEmitter emitter, String question, ConversationContext conv,
                             RetrievalResult retrieval, List<Long> kbIds, long start, Long userId) {
        Long logKbId = kbIds.size() == 1 ? kbIds.get(0) : null;

        // 幻觉兜底：与同步接口同一判定逻辑
        if (retrieval.isEmpty()
                || retrieval.maxVectorSimilarity() < props.getRetrieval().getMinSimilarity()) {
            sendEvent(emitter, "message", FALLBACK_ANSWER);
            sendEvent(emitter, "sources", "[]");
            qaLogService.save(userId, logKbId, conv.convId(), question, FALLBACK_ANSWER, List.of(), "",
                    true, System.currentTimeMillis() - start);
            emitter.complete();
            return;
        }

        String context = buildContext(retrieval.chunks());
        String systemPrompt = props.getPromptTemplate()
                .replace("{context}", context)
                .replace("{history}", conv.history().isBlank() ? "（无）" : conv.history())
                .replace("{question}", question);
        List<SourceVO> sources = retrieval.chunks().stream().map(this::toSource).toList();

        streamChat(new StreamJob(emitter, question, systemPrompt, sources, context, conv.convId(), logKbId,
                start, userId, new AtomicBoolean(false), new AtomicBoolean(false)));
        // 给这个 emitter 设置一个规则：如果它超时了，就自动执行关闭操作。
        emitter.onTimeout(emitter::complete);
    }

    /** 发起一次流式 LLM 调用；未输出任何 token 就失败时自动重试一次（已出字重试会导致前端重复内容） */
    private void streamChat(StreamJob job) {
        streamingChatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(job.systemPrompt()), UserMessage.from(job.question()))
                .build(), new StreamingChatResponseHandler() {
            /**
             * 触发时机：大模型每生成一个 token（一个词或一个字），就会调用一次这个方法。
             * 效果：前端收到后立即渲染，用户看到的就是一个字一个字往外蹦的打字机效果。
             */
            @Override
            public void onPartialResponse(String partial) {
                job.gotToken().set(true);
                sendEvent(job.emitter(), "message", partial);
            }

            /**
             * 触发时机：大模型全部生成完毕。
             * 从完整响应里提取出最终的完整答案文本。注意，虽然前面已经逐字推送过了，但这里拿到的是完整的、拼接好的答案，用于落库审计。
            */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                String answer = response.aiMessage().text();
                try {
                    sendEvent(job.emitter(), "sources", objectMapper.writeValueAsString(job.sources()));
                    qaLogService.save(job.userId(), job.logKbId(), job.convId(), job.question(), answer,
                            job.sources(), job.context(), answer.contains("没有找到相关资料"),
                            System.currentTimeMillis() - job.start());
                } catch (Exception e) {
                    // 异常不能穿出回调：langchain4j 会转成 onError，而 emitter 已 complete
                    log.error("流式问答落库/溯源失败", e);
                } finally {
                    job.emitter().complete();
                }
            }

            /**
             * 触发时机：大模型调用过程中发生异常（比如 API 超时、网络中断、余额不足等）。
             * 目的：留下失败记录，方便日后统计失败率、排查问题。
             */
            @Override
            public void onError(Throwable error) {
                // 外部 API 连接重置/超时时：还没吐过任何内容才重试（重试上限 1 次），
                // 已输出 token 再重发会让前端出现重复段落
                if (!job.gotToken().get() && job.retried().compareAndSet(false, true)) {
                    log.warn("流式调用失败且尚未输出内容，自动重试一次: {}", error.getMessage());
                    streamChat(job);
                    return;
                }
                log.error("流式回答失败", error);
                sendEvent(job.emitter(), "error", "回答生成失败: " + error.getMessage());
                qaLogService.save(job.userId(), job.logKbId(), job.convId(), job.question(), "", List.of(),
                        job.context(), false, System.currentTimeMillis() - job.start());
                job.emitter().complete();
            }
        });
    }

    /** SSE 推送：客户端提前断开（IOException）时静默忽略 */
    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            log.warn("SSE 推送失败，客户端可能已断开: {}", e.getMessage());
        }
    }

    /** SSE 推送 JSON 载荷：序列化失败只记日志，不能影响流 */
    private void sendJson(SseEmitter emitter, String name, Object payload) {
        try {
            sendEvent(emitter, name, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("SSE 事件[{}]序列化失败: {}", name, e.getMessage());
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
        return new SourceVO(c.getDocId(), c.getFileName(), c.getKbName(), c.getChunkIndex(),
                c.getContent(), c.getScore(), c.getHeadingPath(), c.getMatchedTerms());
    }

    /** 上下文拼接：给来源片段编号，方便模型在回答中引用【来源】 */
    /**
     * 把系统检索到的零散文档碎片，拼成一段排版整齐的文字，准备喂给大模型。
     */
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
