package edu.platform.service.vo;

import java.time.LocalDateTime;

/** 教师视角的提交行：{@code {subId, assignId, studentId, studentName, content, submitTime, grade, feedback}}。 */
public record SubmissionVO(
        Long subId,
        Long assignId,
        String studentId,
        String studentName,
        String content,
        LocalDateTime submitTime,
        Double grade,
        String feedback) {
}
