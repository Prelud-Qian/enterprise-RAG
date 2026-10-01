package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.dto.AskRequest;
import com.enterprise.rag.entity.vo.AskResponse;
import com.enterprise.rag.service.QaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 统一问答接口：不指定知识库，后端自动路由到最相关的库检索（当前用户可见范围内）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AskController {

    private final QaService qaService;

    /** 统一提问：返回回答 + 引用来源（含来源所属知识库名）+ 是否触发兜底 */
    @PostMapping("/ask")
    public Result<AskResponse> ask(@Valid @RequestBody AskRequest req) {
        return Result.ok(qaService.ask(req.getQuestion(), req.getConversationId()));
    }

    /**
     * 统一流式提问（SSE）：事件序与错误通道规则同 /api/kb/{kbId}/ask/stream
     * （meta 会话 id → message 逐 token → sources 溯源 JSON；同步异常返 JSON）
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody AskRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时
        qaService.askStream(req.getQuestion(), req.getConversationId(), emitter);
        return emitter;
    }
}
