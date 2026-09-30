package edu.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 系统用户（学生 / 教师 / 管理员）。
 *
 * <p>表名是 {@code sys_user}：{@code user} 在 H2/MySQL 里都是保留字。
 */
@Getter
@Setter
@Entity
@Table(name = "sys_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt 摘要，绝不返回给前端。 */
    @Column(nullable = false)
    private String password;

    private String realName;

    /** student | teacher | admin */
    private String role;

    private String studentId;

    private String teacherId;

    private String major;

    private String title;

    private LocalDateTime createdAt;
}
