package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.dto.AskRequest;
import com.enterprise.rag.entity.vo.AskResponse;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.entity.vo.QaLogVO;
import com.enterprise.rag.entity.vo.SearchResponse;
import com.enterprise.rag.service.QaLogService;
import com.enterprise.rag.service.QaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答接口：提问（RAG 全链路）/ 问答日志（审计）
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;
    private final QaLogService qaLogService;

    /** 提问：返回回答 + 引用来源（溯源）+ 是否触发兜底 */
    @PostMapping("/{kbId}/ask")
    public Result<AskResponse> ask(@PathVariable Long kbId, @Valid @RequestBody AskRequest req) {
        return Result.ok(qaService.ask(kbId, req.getQuestion()));
    }

    /**
     * 流式提问（SSE）：答案逐 token 返回，事件 message=内容，sources=溯源 JSON。
     * 流式接口返回 SseEmitter，不经过统一 Result 包装
     */
    @PostMapping(value = "/{kbId}/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@PathVariable Long kbId, @Valid @RequestBody AskRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时
        qaService.askStream(kbId, req.getQuestion(), emitter);
        return emitter;
    }

    /** 仅检索（不调 LLM）：调试检索效果、跑评测脚本用 */
    @PostMapping("/{kbId}/search")
    public Result<SearchResponse> search(@PathVariable Long kbId, @Valid @RequestBody AskRequest req) {
        return Result.ok(qaService.search(kbId, req.getQuestion()));
    }

    /** 问答日志分页（审计） */
    @GetMapping("/{kbId}/qa-logs")
    public Result<PageVO<QaLogVO>> logs(@PathVariable Long kbId,
                                        @RequestParam(defaultValue = "1") long page,
                                        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(qaLogService.page(kbId, page, size));
    }
}
