package edu.platform.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：自带 HTTP 状态码 + 机器可读的错误码，由 {@link GlobalExceptionHandler} 转成
 * {@code {"error":"CODE","message":"..."}}。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
