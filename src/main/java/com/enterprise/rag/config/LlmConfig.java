package com.enterprise.rag.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型 Bean 装配：
 * 通义千问(qwen-plus) 与 BGE-M3 均通过阿里云百炼 OpenAI 兼容端点
 * （compatible-mode）接入，LangChain4j 统一封装。换模型只需改 yml 的
 * base-url / model，代码零改动。
 */
@Configuration
public class LlmConfig {

    @Bean
    public ChatModel chatLanguageModel(RagProperties props) {
        RagProperties.Llm llm = props.getLlm();
        return OpenAiChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .modelName(llm.getModel())
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .build();
    }

    /** 流式对话模型（SSE 逐 token 返回用，与普通 ChatModel 同配置） */
    @Bean
    public StreamingChatModel streamingChatModel(RagProperties props) {
        RagProperties.Llm llm = props.getLlm();
        return OpenAiStreamingChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .apiKey(llm.getApiKey())
                .modelName(llm.getModel())
                .temperature(llm.getTemperature())
                .maxTokens(llm.getMaxTokens())
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel(RagProperties props) {
        RagProperties.Embedding emb = props.getEmbedding();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(emb.getBaseUrl())
                .apiKey(emb.getApiKey())
                .modelName(emb.getModel())
                .build();
    }
}
