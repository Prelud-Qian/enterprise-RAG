package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 父块摘要生成（RAPTOR 简化版摘要树的上半场）：
 * 入库时用 LLM 为每个父块生成一句话摘要，摘要向量化后存 chunk_summary 表；
 * 检索时先搜摘要确定相关父块范围（见 RetrievalService），再在范围内精检子块。
 * 单块生成失败置 null：该父块无摘要行，检索侧自动降级为全库检索
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SummaryService {

    private final ChatModel chatModel;
    private final RagProperties props;

    private static final String SUMMARY_PROMPT =
            "用一句话概括以下企业文档片段的核心内容，不超过 %d 字，只输出概括本身：\n\n%s";

    public List<String> summarize(List<String> parentContents) {
        RagProperties.Summary s = props.getSummary();
        if (!s.getEnabled()) {
            return parentContents.stream().map(x -> (String) null).toList();
        }
        List<String> result = new ArrayList<>(parentContents.size());
        for (String content : parentContents) {
            try {
                ChatResponse resp = chatModel.chat(ChatRequest.builder()
                        .messages(SystemMessage.from(String.format(SUMMARY_PROMPT, s.getMaxChars(), content)))
                        .build());
                String text = resp.aiMessage().text().trim();
                result.add(text.length() > s.getMaxChars() * 2 ? text.substring(0, s.getMaxChars() * 2) : text);
            } catch (Exception e) {
                log.warn("父块摘要生成失败（该父块降级为无摘要）: {}", e.getMessage());
                result.add(null);
            }
        }
        return result;
    }
}
