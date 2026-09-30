package edu.platform.repo;

import edu.platform.domain.Submission;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    List<Submission> findByAssignId(Long assignId);

    Optional<Submission> findByAssignIdAndStudentId(Long assignId, String studentId);

    List<Submission> findByStudentIdAndAssignIdIn(String studentId, Collection<Long> assignIds);

    long countByAssignId(Long assignId);

    long countByAssignIdAndStudentId(Long assignId, String studentId);

    long countByStudentIdIn(Collection<String> studentIds);

    long countByAssignIdIn(Collection<Long> assignIds);

    /**
     * 优化实现：一条 group by 查出「每个学生在指定若干次作业上的提交次数」。
     *
     * <p>对应 GET /api/benchmark/stats 里的 optimized 分支，与逐条 count 的 naive 分支形成对比。
     */
    @Query(value = """
            select s.student_id as student_id, count(*) as submitted
              from submission s
             where s.assign_id in (:assignIds)
             group by s.student_id
            """, nativeQuery = true)
    List<Object[]> countSubmissionsGroupByStudent(@Param("assignIds") Collection<Long> assignIds);

    /**
     * 优化实现（按课程）：直接关联 assignment 做聚合，省掉「先查作业 id 列表」那一次查询，
     * 因此整个学情统计只需要 2 条 SQL。
     */
    @Query(value = """
            select s.student_id as student_id, count(*) as submitted
              from submission s
              join assignment a on a.assign_id = s.assign_id
             where a.course_id = :courseId and a.status = 1
             group by s.student_id
            """, nativeQuery = true)
    List<Object[]> countSubmissionsGroupByStudentForCourse(@Param("courseId") String courseId);

    @Modifying
    @Transactional
    @Query("delete from Submission s where s.assignId = :assignId")
    int deleteAllByAssignId(@Param("assignId") Long assignId);

    @Modifying
    @Transactional
    @Query("delete from Submission s where s.studentId = :studentId")
    int deleteAllByStudentId(@Param("studentId") String studentId);

    @Modifying
    @Transactional
    @Query("delete from Submission s where s.assignId in (select a.assignId from Assignment a where a.courseId = :courseId)")
    int deleteAllByCourseId(@Param("courseId") String courseId);
}
