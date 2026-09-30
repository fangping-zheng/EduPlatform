package edu.platform.repo;

import edu.platform.domain.CourseSelection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CourseSelectionRepository extends JpaRepository<CourseSelection, Long> {

    List<CourseSelection> findByStudentId(String studentId);

    List<CourseSelection> findByCourseId(String courseId);

    Optional<CourseSelection> findByStudentIdAndCourseId(String studentId, String courseId);

    void deleteByStudentIdAndCourseId(String studentId, String courseId);

    boolean existsByStudentIdAndCourseId(String studentId, String courseId);

    long countByCourseId(String courseId);

    List<CourseSelection> findByCourseIdIn(Collection<String> courseIds);

    @Modifying
    @Transactional
    @Query("delete from CourseSelection cs where cs.courseId = :courseId")
    int deleteAllByCourseId(@Param("courseId") String courseId);

    @Modifying
    @Transactional
    @Query("delete from CourseSelection cs where cs.studentId = :studentId")
    int deleteAllByStudentId(@Param("studentId") String studentId);

    /**
     * 学情完成度统计：一条聚合 SQL 拿到「学生 + 应交作业数 + 已交作业数」。
     *
     * <p>为什么不用循环：朴素实现是「先查学生名单，再对每个学生/每次作业单独 count」，
     * 30 名学生 × 4 次作业就是 120+ 条 SQL；这里把 group by 下沉到数据库，
     * SQL 条数与数据量解耦（对应 GET /api/benchmark/stats 的对比数据）。
     *
     * <p>统计口径：只统计 status=1（进行中）的作业，已撤回的作业不计入「应交」。
     */
    @Query(value = """
            select cs.student_id,
                   u.real_name,
                   (select count(*) from assignment a
                     where a.course_id = :courseId and a.status = 1) as total_assign,
                   count(s.sub_id) as submitted
              from course_selection cs
              join sys_user u on u.student_id = cs.student_id
              left join submission s
                     on s.student_id = cs.student_id
                    and s.assign_id in (select a2.assign_id from assignment a2
                                         where a2.course_id = :courseId and a2.status = 1)
             where cs.course_id = :courseId
             group by cs.student_id, u.real_name
             order by cs.student_id
            """, nativeQuery = true)
    List<Object[]> studentHomeworkStats(@Param("courseId") String courseId);
}
