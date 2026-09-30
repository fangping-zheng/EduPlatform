package edu.platform.repo;

import edu.platform.domain.KnowledgeNode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface KnowledgeNodeRepository extends JpaRepository<KnowledgeNode, Long> {

    List<KnowledgeNode> findByCourseIdOrderByOrderNumAsc(String courseId);

    long countByCourseId(String courseId);

    @Modifying
    @Transactional
    @Query("delete from KnowledgeNode n where n.courseId = :courseId")
    int deleteAllByCourseId(@Param("courseId") String courseId);
}
