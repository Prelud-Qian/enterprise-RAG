package com.enterprise.rag.entity.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 问答接口返回：回答 + 是否兜底 + 引用来源（溯源） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskResponse {

    private String answer;
    /** @JsonProperty 固定 JSON 字段名——Lombok 对 boolean isFallback 生成的 getter 是
     *  isFallback()，Jackson 默认会序列化成 "fallback"，与接口契约不符 */
    @JsonProperty("isFallback")
    private boolean isFallback;
    private List<SourceVO> sources;
}
