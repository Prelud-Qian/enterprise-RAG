package com.enterprise.rag.entity.vo;

import com.enterprise.rag.entity.KnowledgeBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseVO {

    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static KnowledgeBaseVO from(KnowledgeBase kb) {
        return new KnowledgeBaseVO(kb.getId(), kb.getName(), kb.getDescription(),
                kb.getOwnerId(), kb.getCreatedAt(), kb.getUpdatedAt());
    }
}
