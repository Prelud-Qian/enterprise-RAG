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

/**
 * 纯字符串先由 TextSegment.from 包装成 LangChain4j 的 TextSegment，
 * 再交给 withRetry 包裹的模型调用——单条文本走 embed（问答时把用户问题向量化），
 * 多条走 embedBatch（入库时上层按 batch-size=20 分批传入，内部用 embedAll 一次请求多条，减少 API 往返）；
 * withRetry 是唯一的失败处理入口，捕获异常后最多重试 2 次、退避 1s/2s（1000L << attempt），
 * 但 BusinessException 直接向上抛不重试（配置类错误重试没意义），重试耗尽后包成 500 抛出；
 * 模型返回的 Response<Embedding> 经 .content().vector() 取出 float[]，
 * 再由 checkDimension 校验长度是否等于配置的 1024 维——不等就抛 500，
 * 防的是"换 embedding 模型忘改配置"导致错误维度的向量静默写进 pgvector、从此所有检索都是错的；
 * 校验通过后输出 float[]（单条）或 List<float[]>（批量），交回调用方去入库或检索，
 * 其间 sleep 只负责阻塞等待，捕获 InterruptedException 时恢复线程中断标志。
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
