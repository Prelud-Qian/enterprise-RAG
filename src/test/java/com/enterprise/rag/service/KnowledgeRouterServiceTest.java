package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRouterServiceTest {

    @Mock
    private ChatModel chatModel;

    private KnowledgeRouterService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRouterService(chatModel, new RagProperties());
    }

    @Test
    @DisplayName("解析路由输出：剔除候选集外的幻觉 id、去重、兼容全角竖线、截断到 topN")
    void 解析路由输出() {
        Set<Long> valid = Set.of(1L, 2L, 3L);
        assertEquals(List.of(2L, 3L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n3|考勤制度\n2|重复", valid, 3));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n9|幻觉id", valid, 3));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2|休假制度\n1|员工手册\n3|考勤制度", valid, 1));
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("2｜休假制度", valid, 3));
        assertTrue(KnowledgeRouterService.parseRoutingOutput("0|无明显匹配", valid, 3).isEmpty());
        assertTrue(KnowledgeRouterService.parseRoutingOutput("", valid, 3).isEmpty());
        assertTrue(KnowledgeRouterService.parseRoutingOutput(null, valid, 3).isEmpty());
    }

    @Test
    @DisplayName("解析路由输出：兼容行首编号（编号不应与 id 拼成新的数字）")
    void 解析带编号输出() {
        Set<Long> valid = Set.of(2L, 12L);
        assertEquals(List.of(2L),
                KnowledgeRouterService.parseRoutingOutput("1. 2|休假制度", valid, 3));
        assertEquals(List.of(12L),
                KnowledgeRouterService.parseRoutingOutput("2. 12|考勤制度", valid, 3));
    }

    @Test
    @DisplayName("短路：无候选库返回空列表，不调 LLM")
    void 空候选返回空() {
        assertTrue(service.route("年假怎么休", "", List.of()).isEmpty());
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("短路：唯一候选直接返回，不调 LLM")
    void 单库短路不调LLM() {
        assertEquals(List.of(7L), service.route("年假怎么休", "", List.of(kb(7L, "员工手册"))));
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("降级：路由关闭时直接用全部候选库")
    void 路由关闭用全部候选() {
        RagProperties props = new RagProperties();
        props.getRouting().setEnabled(false);
        KnowledgeRouterService disabled = new KnowledgeRouterService(chatModel, props);

        assertEquals(List.of(1L, 2L), disabled.route("年假", "", List.of(kb(1L, "A"), kb(2L, "B"))));
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("降级：LLM 调用失败返回全部候选库")
    void LLM失败降级全部候选() {
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("connection reset"));

        assertEquals(List.of(1L, 2L), service.route("年假", "", List.of(kb(1L, "A"), kb(2L, "B"))));
    }

    @Test
    @DisplayName("正常路由：按模型输出顺序返回命中的库")
    void 正常路由() {
        ChatResponse response = mock(ChatResponse.class);
        when(response.aiMessage()).thenReturn(AiMessage.from("2|休假制度\n1|员工手册"));
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        assertEquals(List.of(2L, 1L),
                service.route("年假怎么休", "问：入职流程", List.of(kb(1L, "员工手册"), kb(2L, "休假制度"))));
    }

    private KnowledgeBaseVO kb(Long id, String name) {
        return new KnowledgeBaseVO(id, name, "描述", 1L, null, null);
    }
}
