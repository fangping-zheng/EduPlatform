package edu.platform.service.vo;

import edu.platform.domain.User;

/** 用户视图对象。教师/管理员的 {@code studentId} 为 null，学生的 {@code teacherId} 为 null。 */
public record UserVO(
        Long id,
        String username,
        String realName,
        String role,
        String studentId,
        String teacherId,
        String major,
        String title) {

    /** 实体 → VO。绝不把 password 带出去。 */
    public static UserVO of(User u) {
        return u == null ? null : new UserVO(u.getId(), u.getUsername(), u.getRealName(), u.getRole(),
                u.getStudentId(), u.getTeacherId(), u.getMajor(), u.getTitle());
    }
}
