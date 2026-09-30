package edu.platform.web;

import org.springframework.http.HttpStatus;

/** 401：未登录 / token 失效。 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String error, String message) {
        super(HttpStatus.UNAUTHORIZED, error, message);
    }
}
