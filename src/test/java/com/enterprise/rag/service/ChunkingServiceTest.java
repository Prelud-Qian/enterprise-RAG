package com.enterprise.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    @Test
    @DisplayName("句子边界切分：总长未超限时合并为一块")
    void 正常合并() {
        String text = "第一句话。第二句话！第三句话？";
        List<String> chunks = service.chunk(text, 100, 0);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一句话"));
        assertTrue(chunks.get(0).contains("第三句话"));
    }

    @Test
    @DisplayName("超长句子硬切：单句超过 chunkSize 时按固定长度切分")
    void 硬切() {
        List<String> chunks = service.chunk("x".repeat(30), 10, 0);
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.length() == 10));
    }

    @Test
    @DisplayName("重叠：下一块开头拼接上一块结尾，保留跨块语义")
    void 重叠() {
        String text = "一二三四五六七八九十。" + "甲乙丙丁戊己庚辛壬癸。";
        List<String> chunks = service.chunk(text, 15, 5);
        assertEquals(2, chunks.size());
        // 第一块 11 字，重叠 5 字：第二块应以第一块末尾 5 字开头
        assertTrue(chunks.get(1).startsWith("七八九十。"));
    }

    @Test
    @DisplayName("空文本与纯空白返回空列表")
    void 空文本() {
        assertTrue(service.chunk("", 100, 0).isEmpty());
        assertTrue(service.chunk("  \n\n  ", 100, 0).isEmpty());
    }

    @Test
    @DisplayName("两级分块：子块是父块的子集，父块上下文更完整")
    void 两级分块() {
        String text = "第一段内容甲乙丙。" + "第二段内容丁戊己。" + "第三段内容庚辛壬。";
        // parentSize 50 → 一个父块；childSize 12 → 每句一个子块
        List<ChunkingService.ChunkPair> pairs = service.chunkWithParents(text, 12, 0, 50);
        assertEquals(3, pairs.size());
        for (ChunkingService.ChunkPair pair : pairs) {
            assertTrue(pair.parent().contains(pair.child()));
            assertTrue(pair.parent().length() > pair.child().length());
        }
    }
}
