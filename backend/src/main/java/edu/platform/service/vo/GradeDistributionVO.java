package edu.platform.service.vo;

import java.util.List;

/** 成绩分段分布（教师工具 getCourseGradeDistribution 的结果）。 */
public record GradeDistributionVO(
        String courseId,
        int totalStudents,
        int gradedCount,
        Double average,
        Double highest,
        Double lowest,
        List<Bucket> buckets) {

    public record Bucket(String range, int count) {
    }
}
