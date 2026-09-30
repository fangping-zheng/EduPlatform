package edu.platform.web;

import org.springframework.http.HttpStatus;

/** 403：登录了，但越权访问别人的数据。 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String error, String message) {
        super(HttpStatus.FORBIDDEN, error, message);
    }
}
