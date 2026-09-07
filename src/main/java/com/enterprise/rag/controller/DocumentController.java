package com.enterprise.rag.controller;

import com.enterprise.rag.common.Result;
import com.enterprise.rag.entity.vo.DocumentVO;
import com.enterprise.rag.entity.vo.PageVO;
import com.enterprise.rag.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口：上传（PDF/Word → 解析 → 分块 → 向量化入库）/ 列表 / 删除
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档。chunkSize / chunkOverlap 可选，不传用 yml 默认值
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DocumentVO> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam("kbId") Long kbId,
                                     @RequestParam(required = false) Integer chunkSize,
                                     @RequestParam(required = false) Integer chunkOverlap) {
        return Result.ok(documentService.upload(kbId, file, chunkSize, chunkOverlap));
    }

    @GetMapping
    public Result<PageVO<DocumentVO>> list(@RequestParam Long kbId,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "10") long size) {
        return Result.ok(documentService.page(kbId, page, size));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok();
    }
}
