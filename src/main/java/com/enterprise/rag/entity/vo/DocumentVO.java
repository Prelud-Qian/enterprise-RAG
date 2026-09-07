package com.enterprise.rag.entity.vo;

import com.enterprise.rag.entity.Document;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentVO {

    private Long id;
    private Long kbId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private LocalDateTime createdAt;

    public static DocumentVO from(Document doc) {
        return new DocumentVO(doc.getId(), doc.getKbId(), doc.getFileName(), doc.getFileType(),
                doc.getFileSize(), doc.getChunkCount(), doc.getStatus(), doc.getErrorMsg(), doc.getCreatedAt());
    }
}
