package edu.platform.web.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /api/student/assignments/{assignId}/submit 请求体。 */
public record SubmitRequest(@NotBlank(message = "不能为空") String content) {
}
