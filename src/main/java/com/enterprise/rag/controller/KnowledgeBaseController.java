package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.dto.KbRequest;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import com.enterprise.rag.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库接口：创建 / 列表 / 详情 / 删除（数据隔离在 Service 层校验 owner_id）
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public Result<KnowledgeBaseVO> create(@Valid @RequestBody KbRequest req) {
        return Result.ok(knowledgeBaseService.create(req));
    }

    @GetMapping
    public Result<List<KnowledgeBaseVO>> list() {
        return Result.ok(knowledgeBaseService.listMine());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> getById(@PathVariable Long id) {
        return Result.ok(knowledgeBaseService.getById(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.ok();
    }
}
