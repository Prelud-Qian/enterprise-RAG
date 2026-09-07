package com.enterprise.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义分块（Semantic Chunking）：
 * 1. 优先按段落(\n\n)/句子(。！？；)边界切分 —— 保证片段落在语义完整处
 * 2. 句子合并到 chunkSize 上限，超长句子才硬切
 * 3. 相邻块之间保留 overlap 重叠，避免检索时语义被切断
 * 分块大小/重叠度可配置（yml 默认 + 上传接口覆盖）
 */
@Service
public class ChunkingService {

    /** 句子边界：中文句末标点 + 换行，lookbehind 保证分隔符留在句尾 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；\\n])");

    public List<String> chunk(String text, int chunkSize, int chunkOverlap) {
        // 归一化：统一换行符、压缩 3 个以上连续换行
        String normalized = text.replace("\r\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }

        // 按语义边界切出句子
        List<String> sentences = Arrays.stream(SENTENCE_SPLIT.split(normalized))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // 贪心合并句子成块：超长句子硬切
        List<String> base = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String s : sentences) {
            if (s.length() > chunkSize) {
                flush(current, base);
                hardSplit(s, chunkSize).forEach(base::add);
            } else if (current.length() + s.length() > chunkSize) {
                flush(current, base);
                current = new StringBuilder(s);
            } else {
                current.append(s);
            }
        }
        flush(current, base);
        if (base.isEmpty()) {
            return List.of();
        }

        // 相邻块加 overlap：下一块开头拼接上一块结尾，保留跨块语义
        List<String> result = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            String part = base.get(i);
            if (i > 0 && chunkOverlap > 0) {
                String prev = base.get(i - 1);
                part = prev.substring(Math.max(0, prev.length() - chunkOverlap)) + part;
            }
            result.add(part);
        }
        return result;
    }

    private void flush(StringBuilder sb, List<String> out) {
        if (!sb.isEmpty()) {
            out.add(sb.toString().trim());
            sb.setLength(0);
        }
    }

    /**
     * 两级分块（small-to-big 检索）：
     * 先按 parentSize 切出父级块，再在每个父级块内按 childSize 切子块。
     * 子块做向量化与检索（定位精准），命中后展开为父级块喂给 LLM（上下文完整）
     */
    public List<ChunkPair> chunkWithParents(String text, int childSize, int childOverlap, int parentSize) {
        List<ChunkPair> pairs = new ArrayList<>();
        for (String parent : chunk(text, parentSize, 0)) {
            for (String child : chunk(parent, childSize, childOverlap)) {
                pairs.add(new ChunkPair(child, parent));
            }
        }
        return pairs;
    }

    /** 子块 + 父级块 */
    public record ChunkPair(String child, String parent) {
    }

    /** 单句超过 chunkSize 时的硬切兜底 */
    private List<String> hardSplit(String longText, int size) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < longText.length(); i += size) {
            parts.add(longText.substring(i, Math.min(longText.length(), i + size)));
        }
        return parts;
    }
}
