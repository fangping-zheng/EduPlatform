package edu.platform.service.vo;

import java.time.LocalDateTime;

/** 作业视图对象（教师端作业管理列表）。 */
public record AssignmentVO(
        Long assignId,
        String courseId,
        String title,
        String description,
        LocalDateTime deadline,
        Integer status,
        LocalDateTime publishTime) {
}
