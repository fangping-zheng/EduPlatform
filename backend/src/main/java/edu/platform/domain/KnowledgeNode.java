package edu.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * 课程知识点（树形，用 parent_id 自关联）。
 *
 * <p>刻意不使用 @ManyToOne/@OneToMany 自关联：树只有一层父子关系、数据量很小，
 * 一次全量查询后在内存里拼树比让 Hibernate 逐层懒加载更简单、也不会踩 N+1。
 */
@Getter
@Setter
@Entity
@Table(name = "knowledge_node")
public class KnowledgeNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long nodeId;

    private String courseId;

    private Long parentId;

    private String nodeName;

    private String content;

    private Integer orderNum;

    /** 非持久化字段，只用于拼装返回给前端的树。 */
    @Transient
    private List<KnowledgeNode> children = new ArrayList<>();
}
