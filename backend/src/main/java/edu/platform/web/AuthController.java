package edu.platform.web;

import edu.platform.security.CurrentUsers;
import edu.platform.service.AuthService;
import edu.platform.service.vo.UserVO;
import edu.platform.web.dto.LoginRequest;
import edu.platform.web.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证：登录 + 当前用户。 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        return new LoginResponse(result.token(), UserVO.of(result.user()));
    }

    @GetMapping("/me")
    public UserVO me() {
        return UserVO.of(authService.requireUser(CurrentUsers.require().id()));
    }
}
