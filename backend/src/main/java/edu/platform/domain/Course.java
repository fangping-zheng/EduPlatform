package edu.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 课程。主键是业务课程号（如 CS101），不是自增 id。
 *
 * <p>{@code selected} 是「已选人数」的冗余计数列，必须与 course_selection 的实际行数保持一致，
 * 因此所有会改动的入口（选课 / 退课 / 删课）都在悲观锁保护下同时维护这张表的计数。
 */
@Getter
@Setter
@Entity
@Table(name = "course")
public class Course {

    @Id
    private String courseId;

    private String name;

    private Integer credit;

    private String description;

    private String teacherId;

    /** 容量，<=0 视为不限容量。 */
    private Integer capacity;

    /** 已选人数（冗余计数，和 course_selection 行数一致）。 */
    private Integer selected;
}
