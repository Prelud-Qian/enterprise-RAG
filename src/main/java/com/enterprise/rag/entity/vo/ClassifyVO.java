package com.enterprise.rag.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传前自动分类结果：推荐知识库 + 理由。
 * recommendedKbId 为 null 表示未能推荐，前端必须让用户手动选择（不是错误，是降级）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClassifyVO {

    /** 推荐的知识库 id；null = 未推荐，前端走手动选择 */
    private Long recommendedKbId;
    private String recommendedKbName;
    /** 推荐理由（LLM 一句话，或降级原因说明） */
    private String reason;
    /** 是否真的调用了 LLM（false = 唯一候选直通 / 功能关闭 / 降级） */
    private Boolean llmUsed;

    /** 降级结果：不推荐，只带一句原因 */
    public static ClassifyVO degraded(String reason) {
        return new ClassifyVO(null, null, reason, false);
    }
}
