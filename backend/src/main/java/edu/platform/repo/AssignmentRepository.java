package edu.platform.repo;

import edu.platform.domain.Assignment;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByCourseId(String courseId);

    List<Assignment> findByCourseIdAndStatus(String courseId, Integer status);

    List<Assignment> findByCourseIdIn(Collection<String> courseIds);

    List<Assignment> findByCourseIdInAndStatus(Collection<String> courseIds, Integer status);

    long countByCourseIdAndStatus(String courseId, Integer status);

    long countByCourseId(String courseId);

    @Modifying
    @Transactional
    @Query("delete from Assignment a where a.courseId = :courseId")
    int deleteAllByCourseId(@Param("courseId") String courseId);
}
