package edu.platform.security;

import edu.platform.web.UnauthorizedException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 从 SecurityContextHolder 取当前用户，是控制器读取身份的唯一切入口。 */
public final class CurrentUsers {

    private CurrentUsers() {
    }

    public static Optional<AuthUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    public static AuthUser require() {
        return find().orElseThrow(() -> new UnauthorizedException("UNAUTHORIZED", "未登录或登录已过期"));
    }
}
