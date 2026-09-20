package com.enterprise.rag.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AskRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题最长 2000 字符")
    private String question;

    /** 会话 id（多轮对话）：不传则为单轮提问，传则携带最近几轮历史上下文 */
    private Long conversationId;
}
