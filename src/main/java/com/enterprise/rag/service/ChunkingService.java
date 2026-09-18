package com.enterprise.rag.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义分块（Semantic Chunking）+ 标题感知（对标 RAGFlow 文档结构树）：
 * 1. 优先按段落(\n\n)/句子(。！？；)边界切分，超长句子才硬切
 * 2. 识别「第X章/第X条/一、/1.」等标题行：标题强制开始新块，
 *    每个块记录章节路径（如"员工手册 > 第三章 考勤与休假 > 第五条"）
 * 3. 相邻块 overlap 保留跨块语义；标题开头的块跳过 overlap，防止上一块尾巴污染标题
 * 4. 两级分块（small-to-big）：父块 2000 边界同样受标题约束
 */
@Service
public class ChunkingService {

    /** 句子边界：中文句末标点 + 换行，lookbehind 保证分隔符留在句尾 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；\\n])");

    /**
     * 标题行：第X章/第X条、一、/（一）、1./1、开头，且整行不超过 42 字。
     * 不加"无句号"约束——企业文档常见"第一条 xxx。"标题与正文同行的写法，
     * 只要整行够短即视为标题（超长条目退化为正文，随块合并）
     */
    private static final Pattern HEADING = Pattern.compile(
            "^(第[一二三四五六七八九十百零0-9]{1,4}[章条节部分篇].{0,39}"
                    + "|[一二三四五六七八九十]{1,3}、.{0,39}"
                    + "|（[一二三四五六七八九十]{1,3}）.{0,39}"
                    + "|\\d{1,2}[.、].{0,39})$");

    /** 结构化分块结果：子块 + 父块 + 章节路径 + 父块序号（1 起） */
    public record StructuredChunk(String child, String parent, String headingPath, int parentIndex) {
    }

    /** 子块 + 父级块（旧接口，保留给现有测试） */
    public record ChunkPair(String child, String parent) {
    }

    public List<String> chunk(String text, int chunkSize, int chunkOverlap) {
        // 保持旧语义：纯句子合并，首句不做标题处理（标题感知仅在 chunkStructured 生效）
        List<BaseChunk> base = buildBase(splitSentences(text), chunkSize, false);
        return applyOverlap(base, chunkOverlap).stream().map(BaseChunk::text).toList();
    }

    /** 两级分块（small-to-big），标题感知版本，供 DocumentService 入库使用；
     *  parentSize <= childSize 时退化为单级（parent=null、parentIndex=0） */
    public List<StructuredChunk> chunkStructured(String text, int childSize, int childOverlap, int parentSize) {
        if (parentSize <= childSize) {
            return applyOverlap(buildBase(splitSentences(text), childSize, true), childOverlap).stream()
                    .map(b -> new StructuredChunk(b.text(), null, b.path(), 0))
                    .toList();
        }
        List<BaseChunk> parents = buildBase(splitSentences(text), parentSize, true);
        List<StructuredChunk> result = new ArrayList<>();
        int parentIndex = 0;
        for (BaseChunk parent : parents) {
            parentIndex++;
            // 子块在父块片段内再分：片段首句可能是正文（不按标题处理），
            // 无标题区域的子块继承父块路径
            List<BaseChunk> children = applyOverlap(buildBase(splitSentences(parent.text()), childSize, false), childOverlap);
            for (BaseChunk child : children) {
                // 子块路径若只含片段内层级（如仅"第五条"），补上父块的章节上下文（去重防重复）
                String path = child.path().isBlank() ? parent.path()
                        : parent.path().isBlank() ? child.path()
                        : dedupePath(parent.path() + " > " + child.path());
                result.add(new StructuredChunk(child.text(), parent.text(), path, parentIndex));
            }
        }
        return result;
    }

    /** 旧两级分块接口（无路径），保留给现有测试 */
    public List<ChunkPair> chunkWithParents(String text, int childSize, int childOverlap, int parentSize) {
        return chunkStructured(text, childSize, childOverlap, parentSize).stream()
                .map(c -> new ChunkPair(c.child(), c.parent()))
                .toList();
    }

    // ---------- 内部 ----------

    /** 基础块：文本 + 起始处的章节路径 + 是否以标题开头 */
    private record BaseChunk(String text, String path, boolean startsWithHeading) {
    }

    private List<String> splitSentences(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(SENTENCE_SPLIT.split(normalized))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 标题感知贪心合并：标题句强制开新块；路径栈记录 标题(0)/章(1)/条(2) 三级
     */
    private List<BaseChunk> buildBase(List<String> sentences, int chunkSize, boolean treatFirstAsTitle) {
        List<BaseChunk> base = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentStartsHeading = false;
        String[] comps = new String[3];
        boolean first = true;

        for (String s : sentences) {
            boolean headingLine = isHeadingLine(s);
            if (first && treatFirstAsTitle && !headingLine && s.length() <= chunkSize) {
                // 文档首句视作标题（level 0），写入路径根；超长首句按正文处理（硬切优先）
                flushBase(current, base, comps, currentStartsHeading);
                current = new StringBuilder(s);
                currentStartsHeading = true;
                comps[0] = s;
                comps[1] = null;
                comps[2] = null;
            } else if (headingLine) {
                flushBase(current, base, comps, currentStartsHeading);
                current = new StringBuilder(s);
                currentStartsHeading = true;
                updatePath(comps, s);
            } else if (s.length() > chunkSize) {
                flushBase(current, base, comps, currentStartsHeading);
                for (String part : hardSplit(s, chunkSize)) {
                    base.add(new BaseChunk(part, currentPath(comps), false));
                }
                currentStartsHeading = false;
            } else if (current.length() + s.length() > chunkSize) {
                flushBase(current, base, comps, currentStartsHeading);
                current = new StringBuilder(s);
                currentStartsHeading = false;
            } else {
                current.append(s);
            }
            first = false;
        }
        flushBase(current, base, comps, currentStartsHeading);
        return base;
    }

    private void flushBase(StringBuilder sb, List<BaseChunk> out, String[] comps, boolean startsHeading) {
        if (!sb.isEmpty()) {
            out.add(new BaseChunk(sb.toString().trim(), currentPath(comps), startsHeading));
            sb.setLength(0);
        }
    }

    /** 是否为标题行（第X章/条、一、/（一）、1.） */
    private boolean isHeadingLine(String s) {
        return HEADING.matcher(s).matches();
    }

    /** 标题层级：第X章/部分/篇=1，第X条/节=2，一、/（一）/数字列表=2 */
    private int headingLevel(String s) {
        if (s.matches("^第[一二三四五六七八九十百零0-9]+[章部分篇].*")) {
            return 1;
        }
        if (s.matches("^第[一二三四五六七八九十百零0-9]+[条节].*")
                || s.matches("^[一二三四五六七八九十]+、.*")
                || s.matches("^（[一二三四五六七八九十]+）.*")
                || s.matches("^\\d{1,2}[.、].*")) {
            return 2;
        }
        return 0;
    }

    /** 更新路径栈：同级覆盖，下级清空 */
    private void updatePath(String[] comps, String heading) {
        int level = headingLevel(heading);
        if (level <= 0) {
            return;
        }
        comps[level - 1] = heading;
        for (int i = level; i < comps.length; i++) {
            comps[i] = null;
        }
    }

    private String currentPath(String[] comps) {
        List<String> parts = new ArrayList<>();
        for (String c : comps) {
            if (c != null && !c.isBlank()) {
                parts.add(c);
            }
        }
        return parts.isEmpty() ? "" : String.join(" > ", parts);
    }

    /** 路径去重（保序）：父子路径拼接时可能重复同一标题层级 */
    private String dedupePath(String path) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String p : path.split(" > ")) {
            if (!p.isBlank()) {
                seen.add(p);
            }
        }
        return String.join(" > ", seen);
    }

    /** overlap：非标题开头的块拼接上一块结尾 */
    private List<BaseChunk> applyOverlap(List<BaseChunk> base, int overlap) {
        List<BaseChunk> result = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            BaseChunk b = base.get(i);
            if (i > 0 && overlap > 0 && !b.startsWithHeading()) {
                String prev = base.get(i - 1).text();
                String part = prev.substring(Math.max(0, prev.length() - overlap)) + b.text();
                result.add(new BaseChunk(part, b.path(), b.startsWithHeading()));
            } else {
                result.add(b);
            }
        }
        return result;
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
