package com.enterprise.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.rag.common.BusinessException;
import com.enterprise.rag.common.LoginUser;
import com.enterprise.rag.dao.mapper.DocumentMapper;
import com.enterprise.rag.dao.mapper.KnowledgeBaseMapper;
import com.enterprise.rag.dao.pg.SummaryDao;
import com.enterprise.rag.dao.pg.VectorStoreDao;
import com.enterprise.rag.entity.Document;
import com.enterprise.rag.entity.KnowledgeBase;
import com.enterprise.rag.entity.dto.KbRequest;
import com.enterprise.rag.entity.vo.KnowledgeBaseVO;
import com.enterprise.rag.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库管理 + 数据隔离核心
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final VectorStoreDao vectorStoreDao;
    private final SummaryDao summaryDao;
    private final Bm25IndexService bm25IndexService;

    public KnowledgeBaseVO create(KbRequest req) {
        LoginUser user = SecurityUtil.currentUser();
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription());
        kb.setOwnerId(user.id());
        knowledgeBaseMapper.insert(kb);
        return KnowledgeBaseVO.from(kb);
    }

    /** 普通用户只看自己的知识库，ADMIN 看全部 */
    public List<KnowledgeBaseVO> listMine() {
        LoginUser user = SecurityUtil.currentUser();
        LambdaQueryWrapper<KnowledgeBase> wrapper =
                new LambdaQueryWrapper<KnowledgeBase>().orderByDesc(KnowledgeBase::getCreatedAt);
        if (!"ADMIN".equals(user.role())) {
            wrapper.eq(KnowledgeBase::getOwnerId, user.id());
        }
        return knowledgeBaseMapper.selectList(wrapper).stream().map(KnowledgeBaseVO::from).toList();
    }

    public KnowledgeBaseVO getById(Long id) {
        return KnowledgeBaseVO.from(requireAccess(id));
    }

    public void delete(Long id) {
        requireAccess(id);
        // 级联清理：文档元数据 → pgvector 片段与摘要 → 知识库本体
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getKbId, id));
        docs.forEach(d -> documentMapper.deleteById(d.getId()));
        vectorStoreDao.deleteByKbId(id);
        summaryDao.deleteByKbId(id);
        knowledgeBaseMapper.deleteById(id);
        bm25IndexService.rebuild(id);
    }

    /**
     * 知识库访问权限校验（RBAC 数据隔离核心）：
     * 不存在 → 404；非 owner 且非 ADMIN → 403。
     * 文档上传/问答/日志等所有按知识库操作的入口都必须先过这里
     */
    public KnowledgeBase requireAccess(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        LoginUser user = SecurityUtil.currentUser();
        if (!"ADMIN".equals(user.role()) && !kb.getOwnerId().equals(user.id())) {
            throw new BusinessException(403, "无权访问该知识库");
        }
        return kb;
    }
}
