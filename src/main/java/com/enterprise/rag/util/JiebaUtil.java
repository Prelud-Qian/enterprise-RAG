package com.enterprise.rag.util;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.List;
import java.util.Locale;

/**
 * jieba 中文分词封装（BM25 索引构建与查询共用，保证两端分词口径一致）
 * SEARCH 模式会把复合词再细分（如"知识库问答"→ 知识/库/问答），提升召回率
 */
public final class JiebaUtil {

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private JiebaUtil() {
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH).stream()
                .map(t -> t.word.trim().toLowerCase(Locale.ROOT))
                // 过滤纯标点/空白 token，只保留有实际含义的词
                .filter(w -> !w.isEmpty()
                        && w.chars().anyMatch(c -> Character.isLetterOrDigit(c) || Character.isIdeographic(c)))
                .toList();
    }
}
