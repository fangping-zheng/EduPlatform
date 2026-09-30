package edu.platform.service;

import edu.platform.domain.User;
import edu.platform.repo.UserRepository;
import edu.platform.security.JwtService;
import edu.platform.web.ApiException;
import edu.platform.web.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 登录与身份查询。 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** 登录成功返回 token + 用户资料（不含密码）。 */
    public LoginResult login(String username, String password) {
        User user = userRepository.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(this::badCredentials);
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPassword())) {
            throw badCredentials();
        }
        return new LoginResult(jwtService.generateToken(user), user);
    }

    public User requireUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UnauthorizedException("USER_NOT_FOUND", "用户不存在或已登录失效"));
    }

    private ApiException badCredentials() {
        // 用户名不存在与密码错误返回同一个错误码，避免账号枚举
        return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
    }

    public record LoginResult(String token, User user) {
    }
}
