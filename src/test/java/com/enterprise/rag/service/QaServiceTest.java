package com.enterprise.rag.service;

import com.enterprise.rag.common.LoginUser;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.ConversationMapper;
import com.enterprise.rag.dao.mapper.QaLogMapper;
import com.enterprise.rag.entity.Conversation;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式问答的事件时序契约：检索等同步步骤必须在首个 SSE 事件之前完成 ——
 * 否则异常时响应已提交，既发不出 error 事件也写不进 JSON，前端只能拿到空白气泡。
 */
@ExtendWith(MockitoExtension.class)
class QaServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private KnowledgeRouterService knowledgeRouterService;
    @Mock
    private QaLogService qaLogService;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private QaLogMapper qaLogMapper;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ChatModel chatModel;
    @Mock
    private StreamingChatModel streamingChatModel;

    private QaService qaService;

    @BeforeEach
    void setUp() {
        qaService = new QaService(knowledgeBaseService, retrievalService, knowledgeRouterService, qaLogService,
                conversationMapper, qaLogMapper, rateLimitService, chatModel, streamingChatModel,
                new ObjectMapper(), new RagProperties());
        // 模拟 JwtAuthFilter 放入登录用户；askStream 在请求线程读它
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(5L, "tester", "USER"), null, List.of()));
        // 新会话建档后 MyBatis-Plus 会回填自增 id，meta 事件里要用
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(inv -> {
            inv.getArgument(0, Conversation.class).setId(42L);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("旧接口流式：检索失败（如 embedding 欠费）时异常先抛、不发任何 SSE 事件——照走 JSON 错误通道")
    void 旧接口检索失败不发送事件() throws Exception {
        when(retrievalService.retrieve(anyList(), anyString()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(RuntimeException.class, () -> qaService.askStream(1L, "年假几天", null, emitter));
        verify(emitter, never()).send(any(SseEventBuilder.class));
    }

    @Test
    @DisplayName("统一接口流式：检索失败同样先抛异常、不发任何 SSE 事件")
    void 统一接口检索失败不发送事件() throws Exception {
        when(knowledgeBaseService.listMine()).thenReturn(List.of(kb(1L, "A"), kb(2L, "B")));
        when(knowledgeRouterService.route(anyString(), anyString(), anyList())).thenReturn(List.of(1L, 2L));
        when(retrievalService.retrieve(anyList(), anyString()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        SseEmitter emitter = mock(SseEmitter.class);

        assertThrows(RuntimeException.class, () -> qaService.askStream("年假几天", null, emitter));
        verify(emitter, never()).send(any(SseEventBuilder.class));
    }

    @Test
    @DisplayName("流式事件序：meta 仍是第一帧，兜底路径按 meta→message→sources 发送")
    void 流式兜底事件序() throws Exception {
        when(retrievalService.retrieve(anyList(), anyString())).thenReturn(RetrievalResult.empty());

        SseEmitter emitter = mock(SseEmitter.class);
        qaService.askStream(1L, "今天天气怎么样", null, emitter);

        ArgumentCaptor<SseEventBuilder> captor = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter, times(3)).send(captor.capture());
        // build() 把事件名行与数据行拆成多个条目，按帧拼回后断言
        List<String> frames = captor.getAllValues().stream()
                .map(b -> b.build().stream().map(d -> String.valueOf(d.getData()))
                        .collect(Collectors.joining()))
                .toList();
        assertTrue(frames.get(0).contains("event:meta"), "第一帧必须是 meta");
        assertTrue(frames.get(0).contains("conversationId"), "meta 携带会话 id");
        assertTrue(frames.get(1).contains("没有找到相关资料"), "第二帧是兜底话术，不调 LLM");
        assertTrue(frames.get(2).contains("event:sources"), "第三帧是 sources");
        assertTrue(frames.get(2).contains("[]"), "兜底时 sources 为空数组");
    }

    private KnowledgeBaseVO kb(Long id, String name) {
        return new KnowledgeBaseVO(id, name, "描述", 1L, null, null);
    }
}
