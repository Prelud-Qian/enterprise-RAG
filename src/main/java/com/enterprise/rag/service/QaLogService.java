package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.rag.config.RagProperties;
import com.enterprise.rag.dao.mapper.QaLogMapper;
import com.enterprise.rag.entity.QaLog;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.entity.vo.QaLogVO;
import com.enterprise.rag.entity.vo.SourceVO;
import com.enterprise.rag.util.SecurityUtil;
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

    public void save(Long kbId, String question, String answer, List<SourceVO> sources,
                     String context, boolean fallback, long latencyMs) {
        try {
            QaLog qaLog = new QaLog();
            qaLog.setUserId(SecurityUtil.currentUser().id());
            qaLog.setKbId(kbId);
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
        knowledgeBaseService.requireAccess(kbId);
        Page<QaLog> p = qaLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<QaLog>()
                        .eq(QaLog::getKbId, kbId)
                        .orderByDesc(QaLog::getCreatedAt));
        Page<QaLogVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(QaLogVO::from).toList());
        return PageVO.of(voPage);
    }
}
