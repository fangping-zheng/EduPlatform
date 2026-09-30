package edu.platform.service.vo;

import java.util.List;

/** 知识点树节点（children 递归，叶子节点为空数组）。 */
public record KnowledgeVO(
        Long nodeId,
        String courseId,
        Long parentId,
        String nodeName,
        String content,
        Integer orderNum,
        List<KnowledgeVO> children) {
}
