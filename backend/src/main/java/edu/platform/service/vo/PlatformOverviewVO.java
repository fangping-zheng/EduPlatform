package edu.platform.service.vo;

/** 平台总览（管理员工具 getPlatformOverview 的结果）。 */
public record PlatformOverviewVO(
        long userCount,
        long adminCount,
        long teacherCount,
        long studentCount,
        long courseCount,
        long selectionCount,
        long assignmentCount,
        long submissionCount) {
}
