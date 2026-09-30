package edu.platform.web.dto;

/** 课程表单（POST/PUT /api/admin/courses）。 */
public record CourseForm(
        String courseId,
        String name,
        Integer credit,
        String description,
        String teacherId,
        Integer capacity) {
}
