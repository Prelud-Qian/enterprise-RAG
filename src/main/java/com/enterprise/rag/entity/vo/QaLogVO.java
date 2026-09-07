package com.enterprise.rag.entity.vo;

import com.enterprise.rag.entity.QaLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QaLogVO {

    private Long id;
    private Long userId;
    private Long kbId;
    private String question;
    private String answer;
    /** 引用来源 JSON 字符串 */
    private String sources;
    private String model;
    private Integer isFallback;
    private Integer latencyMs;
    private LocalDateTime createdAt;

    public static QaLogVO from(QaLog log) {
        return new QaLogVO(log.getId(), log.getUserId(), log.getKbId(), log.getQuestion(),
                log.getAnswer(), log.getSources(), log.getModel(), log.getIsFallback(),
                log.getLatencyMs(), log.getCreatedAt());
    }
}
