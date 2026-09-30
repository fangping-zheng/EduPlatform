package edu.platform.security;

/**
 * 当前登录用户的轻量身份对象。
 *
 * <p>由 JWT 直接构造（不查库），因此 {@code major}/{@code title} 在 token 里没有携带时为 null，
 * 需要完整用户资料时用 {@code AuthService.requireUser(id)} 再查一次。
 *
 * <p>业务层一律以这个对象为准取 studentId / teacherId —— 绝不从请求参数里取学号/工号，
 * 这是「只能操作自己的数据」这条权限边界的实现方式。
 */
public record AuthUser(
        Long id,
        String username,
        String realName,
        String role,
        String studentId,
        String teacherId,
        String major,
        String title) {
}
