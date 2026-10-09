package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.QaLogMapper;
import com.enterprise.rag.entity.QaLog;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.entity.vo.QaLogVO;
import com.enterprise.rag.entity.vo.SourceVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问答日志：审计问答全过程（问题/答案/检索上下文/引用来源/耗时）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QaLogService {

    private final QaLogMapper qaLogMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;
    private final RagProperties props;

    /**
     * userId 由调用方传入，不在此处取 SecurityUtil —— SSE 回调运行在 langchain4j 线程池上，
     * 那里没有 SecurityContext ThreadLocal，现取会抛 401。
     */
    public void save(Long userId, Long kbId, Long conversationId, String question, String answer,
                     List<SourceVO> sources, String context, boolean fallback, long latencyMs) {
        try {
            QaLog qaLog = new QaLog();
            qaLog.setUserId(userId);
            qaLog.setKbId(kbId);
            qaLog.setConversationId(conversationId);
            qaLog.setQuestion(question);
            qaLog.setAnswer(answer);
            qaLog.setSources(objectMapper.writeValueAsString(sources));
            qaLog.setRetrievedContext(context);
            qaLog.setModel(props.getLlm().getModel());
            qaLog.setIsFallback(fallback ? 1 : 0);
            qaLog.setLatencyMs((int) latencyMs);
            qaLogMapper.insert(qaLog);
        } catch (JsonProcessingException e) {
            log.error("问答日志序列化失败", e);
        }
    }

    public PageVO<QaLogVO> page(Long kbId, long page, long size) {
        // 审计日志含他人问答原文，保持管理权限（owner/ADMIN）
        knowledgeBaseService.requireManage(kbId);
        Page<QaLog> p = qaLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<QaLog>()
                        .eq(QaLog::getKbId, kbId)
                        .orderByDesc(QaLog::getCreatedAt));
        Page<QaLogVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(QaLogVO::from).toList());
        return PageVO.of(voPage);
    }
}
