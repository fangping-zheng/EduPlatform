package edu.platform.web.dto;

/**
 * 用户表单（POST/PUT /api/admin/users）。
 *
 * <p>PUT 时 {@code password} 为空字符串表示「不修改密码」。
 */
public record UserForm(
        String username,
        String password,
        String realName,
        String role,
        String studentId,
        String teacherId,
        String major,
        String title) {
}
