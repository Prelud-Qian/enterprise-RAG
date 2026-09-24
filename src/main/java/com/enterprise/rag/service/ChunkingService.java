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

/**
 * splitSentences 先做规范化、并按 。！？；换行 把全文切成句子列表；chunkStructured 拿到句子列表开始编排
 * ——第一遍把整篇喂给合并器 buildBase（size=2000）拼出父块，
 * 第二遍逐个把父块文本重新喂回同一个 buildBase（size=500）切出子块（若 parentSize ≤ childSize 则跳过两级、直接单级返回）；
 * buildBase 内部逐句贪心累加，每句先经 isHeadingLine/headingLevel 判断是不是标题，是标题就 updatePath 更新三层路径栈并强制开新块，
 * 单句超长则交给 hardSplit 定长硬切，攒到装不下就调 flushBase 把缓冲区收口成块并清空；每个父块的子块切完后，
 * 立刻由 applyOverlap 给其中的非标题块开头拼上上一块结尾的 50 字；
 * 最后由 currentPath/dedupePath 把父子路径拼接去重，输出 StructuredChunk(子块文本, 父块文本, 章节路径, 父块序号) 列表。
 */

/**
 * 1. `splitSentences(text)`：用正则 `(?<=[。！？；\n])` 把文本切成句子列表（保留句末标点）
 * 2. `buildBase(sentences, chunkSize, treatFirstAsTitle)`：核心循环，逐句处理三种情况
 *    - **是标题** → 当前块收口，标题开新块（标题永远是新块的起点）
 *    - **单句超长** → 硬切成多个 chunkSize 大小的块
 *    - **正常句子** → 贪心往当前块里追加，超 chunkSize 就收口开新块
 * 3. `isHeadingLine / headingLevel / updatePath`：标题识别与**章节路径**维护
 *    - 路径是个三级栈：标题(0) / 章(1) / 条(2)，同级覆盖、下级清空
 *    - 产物就是溯源里的 `headingPath`："员工手册 > 第三章 考勤与休假 > 第五条"
 * 4. `applyOverlap`：下一块开头拼上一块结尾 50 字；**标题开头的块跳过 overlap**（防止上一块尾巴污染标题）
 * 5. `chunkStructured`：两级编排——先按 parentSize 切父块，再在每个父块里切子块；子块路径与父块路径合并去重
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
    /**
     * 将原始文本先切成大块（父块），再把每个父块切成小块（子块）。子块用于向量检索，父块用于提供上下文。
     * @param text          待分块的原始文本
     * @param childSize     子块的目标大小
     * @param childOverlap  子块之间的重叠大小
     * @param parentSize    父块的目标大小
     * @return
     */
    public List<StructuredChunk> chunkStructured(String text, int childSize, int childOverlap, int parentSize) {
        // 父块大小 ≤ 子块大小时，两级分块没有意义（父块不会比子块大），退化为单级分块。
        if (parentSize <= childSize) {
            return applyOverlap(buildBase(splitSentences(text), childSize, true), childOverlap).stream()
                    .map(b -> new StructuredChunk(b.text(), null, b.path(), 0))
                    .toList();
        }
        // 构建父块列表
        List<BaseChunk> parents = buildBase(splitSentences(text), parentSize, true);
        // 初始化结果容器
        // result：存放最终的子块列表。
        List<StructuredChunk> result = new ArrayList<>();
        // parentIndex：父块索引，从 0 开始，遍历时递增。
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

    /**
     * 为什么要同时有子块和父块：子块小，向量聚焦，检索精度高；父块大，内容完整，上下文充足。
     * 检索时用子块匹配，返回时用父块提供上下文，两者兼顾，解决了“小块检索准但上下文不足、大块上下文全但检索不准”的矛盾。
     *
     * 为什么要有路径：路径记录块在文档中的位置（如“第一章 > 第三条”），
     * 用于来源追溯、过滤排序、上下文补全，同时给大模型提供语义线索，帮助更准确地理解和回答。
     */

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

    /**
     * 把一段原始文本做规范化处理后，按标点切分成"句子"列表，并过滤掉空白片段。
     * @param text
     * @return
     */
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
     * 把句子列表按 chunkSize 合并成块，同时识别标题、维护标题路径，并在必要时进行硬切分。
     */
    /**
     * sentences	        List<String>	    已切分的句子列表
     * chunkSize	        int	                每个块的目标大小
     * treatFirstAsTitle	boolean	            是否把文档首句视作标题
     * @param sentences
     * @param chunkSize
     * @param treatFirstAsTitle
     * @return
     */
    private List<BaseChunk> buildBase(List<String> sentences, int chunkSize, boolean treatFirstAsTitle) {
        // 存放已完成的块
        List<BaseChunk> base = new ArrayList<>();
        // 当前正在构建的块文本
        StringBuilder current = new StringBuilder();
        // 当前块是否以标题开头
        boolean currentStartsHeading = false;
        // 标题路径的三个层级（如 chapter > section > subsection）
        // chapter（章）
        //  └── section（节）
        //        └── subsection（小节）
        String[] comps = new String[3];
        // 是否是第一个句子
        boolean first = true;

        // 遍历每个句子 s
        for (String s : sentences) {
            // 判断这个句子是否是标题行
            boolean headingLine = isHeadingLine(s);
            // first：是第一个句子。
            // treatFirstAsTitle：调用方要求把首句视作标题。
            // !headingLine：首句本身不是标题行（否则走分支 2）。
            // s.length() <= chunkSize：首句长度不超过 chunkSize（超长首句按正文处理）
            /**
             * 首句视作标题
             */
            if (first && treatFirstAsTitle && !headingLine && s.length() <= chunkSize) {
                // 文档首句视作标题（level 0），写入路径根；超长首句按正文处理（硬切优先）
                flushBase(current, base, comps, currentStartsHeading);
                current = new StringBuilder(s);
                currentStartsHeading = true;
                comps[0] = s;
                comps[1] = null;
                comps[2] = null;
            /**
             * 遇到标题行
             */
            } else if (headingLine) {
                flushBase(current, base, comps, currentStartsHeading);
                current = new StringBuilder(s);
                currentStartsHeading = true;
                updatePath(comps, s);
            /**
             * 超长句子
             */
            } else if (s.length() > chunkSize) {
                flushBase(current, base, comps, currentStartsHeading);
                for (String part : hardSplit(s, chunkSize)) {
                    base.add(new BaseChunk(part, currentPath(comps), false));
                }
                currentStartsHeading = false;
            /**
             * 当前块已满
             */
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

    /**
     * 把当前 StringBuilder 缓冲区里累积的文本"定型"成一个 BaseChunk 输出，然后清空缓冲区，准备攒下一段。
     * 它是一个缓冲区冲刷器（flusher） —— 分块过程中的辅助工具方法。
     * @param sb    正在累积的文本缓冲区，攒够一段就"冲"出去
     * @param out   输出列表，冲出来的分块往这里加
     * @param comps 章节层级数组（chapter/section/subsection 等），用于拼路径
     * @param startsHeading 这一块是否以标题开头
     */
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
