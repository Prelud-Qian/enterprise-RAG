package com.enterprise.rag.entity.vo;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 统一分页返回结构 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageVO<T> {

    private long total;
    private long current;
    private long size;
    private List<T> records;

    public static <T> PageVO<T> of(Page<T> page) {
        return new PageVO<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }
}
