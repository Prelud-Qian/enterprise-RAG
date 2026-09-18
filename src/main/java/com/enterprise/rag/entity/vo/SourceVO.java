package com.enterprise.rag.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 引用来源片段（溯源信息） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceVO {

    /** 文档 id（MySQL document 表） */
    private Long docId;
    private String fileName;
    /** 片段序号（从 1 开始） */
    private Integer chunkIndex;
    /** 片段原文 */
    private String content;
    /** 融合得分（RRF/精排），用于排序展示 */
    private Double score;
    /** 章节路径（标题感知分块，如"员工手册 > 第三章 考勤与休假 > 第五条"） */
    private String headingPath;
    /** BM25 命中的查询词（关键词高亮/可解释性，纯向量命中为空） */
    private List<String> matchedTerms;
}
