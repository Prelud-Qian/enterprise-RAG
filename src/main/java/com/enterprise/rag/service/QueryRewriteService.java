package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Query 改写（多查询检索）：
 * 用户口语化问法往往和文档书面写法不一致（"怎么涨工资" vs "调薪制度"），
 * 单查询容易召回不到。用 LLM 把问题改写成多个语义等价的检索查询，
 * 每个查询独立召回、RRF 跨查询累积，显著提升召回鲁棒性。
 * LLM 失败时降级为仅用原始问题，不影响主链路。
 */
@Service
@Slf4j
public class QueryRewriteService {

    private static final String REWRITE_PROMPT = """
            你是检索查询改写助手。把用户问题改写成最多 %d 个语义不同、适合知识库检索的查询，
            每个查询占一行，不要编号、不要解释，直接输出查询本身。
            如果原始问题本身就很适合检索，只输出它即可。
            """;

    private final ChatModel chatModel;
    private final RagProperties props;

    public QueryRewriteService(ChatModel chatModel, RagProperties props) {
        this.chatModel = chatModel;
        this.props = props;
    }

    /** 返回 [原始问题, 改写查询1, 改写查询2, ...]，原始问题永远在首位参与检索 */
    public List<String> rewrite(String question) {
        RagProperties.QueryRewrite qr = props.getRetrieval().getQueryRewrite();
        if (!qr.getEnabled()) {
            return List.of(question);
        }
        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(SystemMessage.from(REWRITE_PROMPT.formatted(qr.getMaxQueries())),
                            UserMessage.from(question))
                    .build());
            List<String> queries = Arrays.stream(response.aiMessage().text().split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.replaceFirst("^\\d+[.、)]\\s*", ""))   // 去掉模型可能输出的编号
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .limit(qr.getMaxQueries())
                    .toList();

            List<String> result = new ArrayList<>(queries.size() + 1);
            result.add(question);
            queries.stream().filter(q -> !q.equals(question)).forEach(result::add);
            if (result.size() > 1) {
                log.debug("Query 改写: {} -> {}", question, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("Query 改写失败，降级为原始问题: {}", e.getMessage());
            return List.of(question);
        }
    }
}
