package edu.platform.repo;

import edu.platform.domain.User;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    List<User> findByRole(String role);

    boolean existsByUsername(String username);

    long countByRole(String role);

    Optional<User> findByStudentId(String studentId);

    Optional<User> findByTeacherId(String teacherId);

    /** 批量按学号取学生，用于「一次查询拿到名单」而不是逐个查（消除 N+1）。 */
    List<User> findByStudentIdIn(Collection<String> studentIds);

    /** 批量按工号取教师，用于把 teacherId 映射成教师姓名。 */
    List<User> findByTeacherIdIn(Collection<String> teacherIds);

    List<User> findByRoleOrderByIdAsc(String role);
}
