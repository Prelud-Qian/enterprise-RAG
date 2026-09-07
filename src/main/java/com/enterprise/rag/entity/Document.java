package com.enterprise.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档表 document（只存元数据，片段正文在 PostgreSQL document_chunk）
 */
@Data
@TableName("document")
public class Document {

    /** 状态：解析中 */
    public static final String STATUS_PARSING = "PARSING";
    /** 状态：就绪 */
    public static final String STATUS_READY = "READY";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String fileName;
    /** pdf / doc / docx */
    private String fileType;
    /** 字节数 */
    private Long fileSize;
    private Integer chunkCount;
    private String status;
    private String errorMsg;
    private Long createdBy;
    private LocalDateTime createdAt;
}
