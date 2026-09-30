package edu.platform.web.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /api/auth/login 请求体。 */
public record LoginRequest(
        @NotBlank(message = "不能为空") String username,
        @NotBlank(message = "不能为空") String password) {
}
