package edu.platform.web.dto;

/** POST /api/teacher/courses/{courseId}/grades 请求体；{@code grade} 为 null 表示清除成绩。 */
public record CourseGradeRequest(String studentId, Double grade) {
}
