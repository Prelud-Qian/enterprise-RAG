package com.enterprise.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话表 conversation（多轮对话：同一会话的问答共享历史上下文） */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private Long userId;
    private LocalDateTime createdAt;
}
