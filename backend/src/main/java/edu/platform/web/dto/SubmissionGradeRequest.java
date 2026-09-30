package edu.platform.web.dto;

/** POST /api/teacher/submissions/{subId}/grade 请求体。 */
public record SubmissionGradeRequest(Double grade, String feedback) {
}
