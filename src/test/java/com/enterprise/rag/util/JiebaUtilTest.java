package com.enterprise.rag.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiebaUtilTest {

    @Test
    @DisplayName("中文分词：SEARCH 模式细分复合词")
    void 中文分词() {
        List<String> tokens = JiebaUtil.tokenize("企业知识库问答系统");
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.contains("企业"));
        // SEARCH 模式会把长词拆成 2~3 字的子词（知识库/问答），提升召回率
        assertTrue(tokens.contains("知识库"));
        assertTrue(tokens.stream().noneMatch(String::isBlank));
    }

    @Test
    @DisplayName("过滤纯标点 token")
    void 过滤标点() {
        List<String> tokens = JiebaUtil.tokenize("你好，世界！");
        assertTrue(tokens.contains("你好"));
        assertTrue(tokens.contains("世界"));
        assertTrue(tokens.stream().noneMatch(t -> t.equals("，") || t.equals("！")));
    }

    @Test
    @DisplayName("英文统一转小写（索引与查询口径一致）")
    void 英文小写() {
        List<String> tokens = JiebaUtil.tokenize("RAG 系统的 ElasticSearch 检索");
        assertTrue(tokens.contains("rag"));
        assertTrue(tokens.contains("elasticsearch"));
        assertFalse(tokens.contains("RAG"));
    }

    @Test
    @DisplayName("空文本返回空列表")
    void 空文本() {
        assertTrue(JiebaUtil.tokenize("").isEmpty());
        assertTrue(JiebaUtil.tokenize(null).isEmpty());
    }
}
