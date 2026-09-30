package edu.platform.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/** POST /api/teacher/courses/{courseId}/assignments 请求体。 */
public record CreateAssignmentRequest(
        @NotBlank(message = "不能为空") String title,
        String description,
        LocalDateTime deadline) {
}
