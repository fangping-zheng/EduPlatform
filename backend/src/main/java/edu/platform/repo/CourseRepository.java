package edu.platform.repo;

import edu.platform.domain.Course;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, String> {

    List<Course> findByTeacherId(String teacherId);

    long countByTeacherId(String teacherId);

    /**
     * 悲观写锁读取课程行。
     *
     * <p>真实 SQL 是 {@code SELECT ... FROM course WHERE course_id = ? FOR UPDATE}：
     * 并发选课 / 退课必须在锁内完成「校验容量 → 写选课记录 → 更新计数」，
     * 否则会出现经典的「检查后写入」竞态导致超卖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.courseId = :id")
    Optional<Course> findByIdForUpdate(@Param("id") String id);
}
