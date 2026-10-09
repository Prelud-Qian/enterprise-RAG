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
 * 知识库管理 + 读写权限校验：
 * 读（问答/文档查看/详情）对所有登录用户开放；管理（建库/删库/传文档/删文档）限 owner 或 ADMIN
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final VectorStoreDao vectorStoreDao;
    private final SummaryDao summaryDao;
    private final Bm25IndexService bm25IndexService;

    /** 建库限 ADMIN：普通用户是查询角色，知识库统一由管理员维护 */
    public KnowledgeBaseVO create(KbRequest req) {
        LoginUser user = SecurityUtil.currentUser();
        if (!"ADMIN".equals(user.role())) {
            throw new BusinessException(403, "仅管理员可创建知识库");
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription());
        kb.setOwnerId(user.id());
        knowledgeBaseMapper.insert(kb);
        return KnowledgeBaseVO.from(kb);
    }

    /** 全部知识库（所有登录用户可见，按创建时间倒序）：列表接口与统一问答路由候选共用 */
    public List<KnowledgeBaseVO> list() {
        LambdaQueryWrapper<KnowledgeBase> wrapper =
                new LambdaQueryWrapper<KnowledgeBase>().orderByDesc(KnowledgeBase::getCreatedAt);
        return knowledgeBaseMapper.selectList(wrapper).stream().map(KnowledgeBaseVO::from).toList();
    }

    public List<KnowledgeBaseVO> listManageable() {
        LoginUser user = SecurityUtil.currentUser();
        LambdaQueryWrapper<KnowledgeBase> wrapper =
                new LambdaQueryWrapper<KnowledgeBase>().orderByDesc(KnowledgeBase::getCreatedAt);
        if (!"ADMIN".equals(user.role())) {
            wrapper.eq(KnowledgeBase::getOwnerId, user.id());
        }
        return knowledgeBaseMapper.selectList(wrapper).stream().map(KnowledgeBaseVO::from).toList();
    }

    public KnowledgeBaseVO getById(Long id) {
        return KnowledgeBaseVO.from(requireRead(id));
    }

    public void delete(Long id) {
        requireManage(id);
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
     * 读权限：不存在 → 404；存在即可（登录由 Security 层保证，读对所有登录用户开放）。
     * 问答/文档列表/库详情等只读入口过这里
     */
    public KnowledgeBase requireRead(Long kbId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        return kb;
    }

    /**
     * 管理权限（RBAC 核心）：不存在 → 404；非 owner 且非 ADMIN → 403。
     * 删库/上传文档/删文档/日志审计等写入口过这里
     */
    public KnowledgeBase requireManage(Long kbId) {
        KnowledgeBase kb = requireRead(kbId);
        LoginUser user = SecurityUtil.currentUser();
        if (!"ADMIN".equals(user.role()) && !kb.getOwnerId().equals(user.id())) {
            throw new BusinessException(403, "无权操作该知识库");
        }
        return kb;
    }
}
