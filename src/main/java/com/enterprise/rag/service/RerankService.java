package com.enterprise.rag.service;

import com.enterprise.rag.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Rerank 精排（二阶段检索的第二阶段）：
 * 向量/BM25 召回（双塔模型，速度快、精度一般）得到候选后，
 * 用交叉编码器模型 gte-rerank 对「问题+片段」逐对打分重排序，
 * 显著提升 Top5 的相关性 —— 这是当前 RAG 生产系统的标配优化。
 *
 * 接口为 OpenAI/Jina 兼容的 /rerank 端点，手写 RestClient 调用；
 * 调用失败自动降级回原 RRF 顺序，精排故障不影响主链路可用性。
 */
@Service
@Slf4j
public class RerankService {

    private final ObjectMapper objectMapper;
    private final RagProperties props;
    private final RestClient restClient;

    public RerankService(ObjectMapper objectMapper, RagProperties props) {
        this.objectMapper = objectMapper;
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 精排 + 截断：按 rerank 相关性得分重排候选片段，取 finalTopK。
     * 候选为空/未启用/调用失败时，保持 RRF 原顺序截断
     */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int finalTopK) {
        RagProperties.Rerank r = props.getRerank();
        if (!r.getEnabled() || candidates.size() <= 1) {
            return candidates.stream().limit(finalTopK).toList();
        }
        try {
            List<String> documents = candidates.stream().map(RetrievedChunk::getContent).toList();
            List<RerankHit> hits = callRerankApi(query, documents);
            // 用精排得分替换 RRF 得分（精排分对用户展示相关性更有意义）
            for (RerankHit hit : hits) {
                candidates.get(hit.index()).setScore(hit.score());
            }
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                    .limit(finalTopK)
                    .toList();
        } catch (Exception e) {
            log.warn("Rerank 调用失败，降级为 RRF 排序: {}", e.getMessage());
            return candidates.stream().limit(finalTopK).toList();
        }
    }

    /** POST {baseUrl}/rerank，兼容两种协议：DashScope(嵌套 input) 与 SiliconFlow(顶层 query/documents) */
    private List<RerankHit> callRerankApi(String query, List<String> documents) throws Exception {
        RagProperties.Rerank r = props.getRerank();
        String baseUrl = StringUtils.hasText(r.getBaseUrl())
                ? r.getBaseUrl() : props.getEmbedding().getBaseUrl();
        String apiKey = StringUtils.hasText(r.getApiKey())
                ? r.getApiKey() : props.getEmbedding().getApiKey();

        Map<String, Object> body;
        if ("siliconflow".equals(r.getApiFormat())) {
            body = Map.of(
                    "model", r.getModel(),
                    "query", query,
                    "documents", documents,
                    "top_n", documents.size(),
                    "return_documents", false);
        } else {
            body = Map.of(
                    "model", r.getModel(),
                    "input", Map.of("query", query, "documents", documents),
                    "parameters", Map.of("return_documents", false));
        }

        String response = restClient.post()
                .uri(baseUrl + "/rerank")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String.class);

        JsonNode results = objectMapper.readTree(response).path("results");
        List<RerankHit> hits = new ArrayList<>();
        for (JsonNode node : results) {
            hits.add(new RerankHit(
                    node.path("index").asInt(),
                    node.path("relevance_score").asDouble()));
        }
        hits.sort(Comparator.comparingInt(RerankHit::index));
        return hits;
    }

    private record RerankHit(int index, double score) {
    }
}
