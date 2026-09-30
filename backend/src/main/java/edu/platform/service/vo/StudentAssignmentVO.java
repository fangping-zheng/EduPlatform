package edu.platform.service.vo;

import java.time.LocalDateTime;

/**
 * 学生视角的作业行，{@code submitted}/{@code submission} 表示「我交了没有」。
 *
 * <p>注意：已撤回（status=0）的作业也会出现在列表里，前端据此显示「已撤回，无法提交」，
 * 但它在 submit 服务里会被 409 ASSIGNMENT_CLOSED 拦下。
 */
public record StudentAssignmentVO(
        Long assignId,
        String courseId,
        String courseName,
        String title,
        String description,
        LocalDateTime deadline,
        Integer status,
        LocalDateTime publishTime,
        Boolean submitted,
        MySubmissionVO submission) {

    /** 学生自己的提交内容（不含评阅人、不含他人信息）。 */
    public record MySubmissionVO(
            Long subId,
            String content,
            LocalDateTime submitTime,
            Double grade,
            String feedback) {
    }
}
