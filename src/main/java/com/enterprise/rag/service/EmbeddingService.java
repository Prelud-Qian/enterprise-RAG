package com.enterprise.rag.service;

import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * 向量化服务：文本 → BGE-M3 向量（1024 维）
 * 带简单重试（模型 API 偶发限流/网络抖动），并校验返回维度防止配错模型
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final EmbeddingModel embeddingModel; // 实际执行向量化的模型对象
    private final RagProperties props; // 读取项目配置

    /** 单条文本向量化（问答时对用户问题使用） */
    public float[] embed(String text) {
        /**
         * TextSegment.from()：将字符串包装成 LangChain4j 的 TextSegment 对象。
         * embeddingModel.embed(...)：调用模型，返回 Response<Embedding>。
         * withRetry(...)：包装调用，提供重试机制。
         */
        Response<Embedding> response = withRetry(() -> embeddingModel.embed(TextSegment.from(text)));
        /**
         * response.content().vector()：从响应中提取 float[] 数组。
         * checkDimension(...)：校验数组长度是否符合配置。
         */
        return checkDimension(response.content().vector());
    }

    /** 批量向量化（文档入库时按 batch-size 分批调用） */
    public List<float[]> embedBatch(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> response = withRetry(() -> embeddingModel.embedAll(segments));
        return response.content().stream()
                .map(e -> checkDimension(e.vector()))
                .toList();
    }

    private float[] checkDimension(float[] vector) {
        if (vector == null || vector.length != props.getEmbedding().getDimension()) {
            throw new BusinessException(500,
                    "向量维度与配置不一致，请检查 rag.embedding.model 是否配错（预期 " + props.getEmbedding().getDimension() + " 维）");
        }
        return vector;
    }

    /** 最多重试 2 次，指数退避 1s/2s */
    private <T> T withRetry(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= 2) {
                    throw new BusinessException(500, "向量化服务调用失败: " + e.getMessage());
                }
                log.warn("向量化调用失败，第 {} 次重试: {}", attempt + 1, e.getMessage());
                sleep(1000L << attempt);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
