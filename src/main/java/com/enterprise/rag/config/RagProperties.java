package com.enterprise.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RAG 全链路配置（application.yml 前缀 rag.*）
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** 对话模型（通义千问） */
    private Llm llm = new Llm();
    /** 向量模型（BGE-M3） */
    private Embedding embedding = new Embedding();
    /** 语义分块默认参数 */
    private Chunk chunk = new Chunk();
    /** 混合检索参数 */
    private Retrieval retrieval = new Retrieval();
    /** Rerank 精排 */
    private Rerank rerank = new Rerank();
    /** 上传限制 */
    private Upload upload = new Upload();
    /** Prompt 模板，占位符 {context} {question} */
    private String promptTemplate;

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String model;
        private Double temperature = 0.1;
        private Integer maxTokens = 1024;
    }

    @Data
    public static class Embedding {
        private String baseUrl;
        private String apiKey;
        private String model = "bge-m3";
        private Integer dimension = 1024;
        private Integer batchSize = 20;
    }

    @Data
    public static class Chunk {
        private Integer size = 500;
        private Integer overlap = 50;
        /** 父级块大小（small-to-big）：0 或 <=size 时禁用父块展开 */
        private Integer parentSize = 2000;
    }

    @Data
    public static class Retrieval {
        private Integer vectorTopK = 10;
        private Integer bm25TopK = 10;
        private Integer finalTopK = 5;
        private Double minSimilarity = 0.4;
        private Integer rrfK = 60;
        /** Query 改写（多查询检索） */
        private QueryRewrite queryRewrite = new QueryRewrite();
    }

    @Data
    public static class QueryRewrite {
        /** 是否启用 LLM 改写（失败自动降级为原始问题） */
        private Boolean enabled = true;
        /** 最多生成的检索查询数（不含原始问题） */
        private Integer maxQueries = 3;
    }

    @Data
    public static class Rerank {
        /** 是否启用精排（关闭时直接按 RRF 顺序取 TopK） */
        private Boolean enabled = true;
        /** 缺省复用 embedding.base-url */
        private String baseUrl;
        /** 缺省复用 embedding.api-key */
        private String apiKey;
        /** 百炼 gte-rerank；硅基流动 BAAI/bge-reranker-v2-m3 */
        private String model = "gte-rerank";
        /** 请求协议：siliconflow（顶层 query/documents）或 dashscope（嵌套 input） */
        private String apiFormat = "dashscope";
        /** RRF 候选取前 N 条参与精排（精排模型按 token 计费，控制成本） */
        private Integer topN = 20;
    }

    @Data
    public static class Upload {
        private Integer maxSizeMb = 20;
        private Integer tikaWriteLimit = 2_000_000;
        private List<String> allowedExtensions = List.of("pdf", "doc", "docx");
        /** true=上传后立即返回，后台线程处理（状态轮询）；false=同步处理完再返回 */
        private Boolean async = false;
    }
}
