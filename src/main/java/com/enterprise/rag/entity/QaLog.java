package com.enterprise.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答日志表 qa_log：审计问答全过程（提问/回答/检索上下文/引用来源）
 */
@Data
@TableName("qa_log")
public class QaLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long kbId;
    /** 所属会话 id（多轮对话，单轮为空） */
    private Long conversationId;
    private String question;
    private String answer;
    /** 引用来源 JSON（SourceVO 数组） */
    private String sources;
    /** 检索到的上下文（Prompt 注入内容） */
    private String retrievedContext;
    private String model;
    /** 1 触发幻觉兜底 0 正常 */
    private Integer isFallback;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
