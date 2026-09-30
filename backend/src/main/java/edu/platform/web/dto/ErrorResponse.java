package edu.platform.web.dto;

/** 统一错误响应体：{@code {"error":"COURSE_FULL","message":"课程已满"}}。 */
public record ErrorResponse(String error, String message) {
}
