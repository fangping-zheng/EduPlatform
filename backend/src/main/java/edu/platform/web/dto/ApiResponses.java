package edu.platform.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 控制器里那些「一行结果」的响应体集合，字段名严格对照 docs/API.md。 */
public final class ApiResponses {

    private ApiResponses() {
    }

    public record SelectResponse(boolean enrolled, int selectedCount) {
    }

    public record IdResponse(Long id) {
    }

    public record AssignIdResponse(Long assignId) {
    }

    public record SubIdResponse(Long subId) {
    }

    public record CourseIdResponse(String courseId) {
    }

    public record StatusResponse(int status) {
    }

    public record DeletedResponse(boolean deleted) {
    }

    public record GradeResponse(String studentId, Double grade) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubmissionGradeResponse(Long subId, Double grade, String feedback) {
    }
}
