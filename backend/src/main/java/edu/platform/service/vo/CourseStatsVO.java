package edu.platform.service.vo;

import java.util.List;

/**
 * 学情完成度统计（GET /api/teacher/courses/{courseId}/stats）。
 *
 * <p>{@code queryCount} 是本次统计真实打到数据库的 SQL 条数（由 SqlCountInspector 实测），
 * 用来在前端直接展示「已消除 N+1」的证据：朴素实现是 1 + 学生数 × 作业数 条，
 * 这里是固定的 2 条（1 条权限/课程校验 + 1 条聚合查询）。
 */
public record CourseStatsVO(
        String courseId,
        Integer totalAssignments,
        Integer totalStudents,
        Integer queryCount,
        List<StudentStatVO> students) {

    public record StudentStatVO(
            String studentId,
            String realName,
            Integer totalAssignments,
            Integer submitted,
            Integer unsubmitted) {
    }
}
