package edu.platform.web.dto;

import edu.platform.service.vo.UserVO;

/** POST /api/auth/login 响应体：{@code {"token":"...","user":{...}}}。 */
public record LoginResponse(String token, UserVO user) {
}
