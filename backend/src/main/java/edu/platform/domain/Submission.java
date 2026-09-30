package edu.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 作业提交。唯一约束 (assign_id, student_id) 保证一名学生对一次作业只有一条记录，
 * 重复提交走「覆盖更新」而不是新增行。
 */
@Getter
@Setter
@Entity
@Table(name = "submission")
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long subId;

    private Long assignId;

    private String studentId;

    private String content;

    private LocalDateTime submitTime;

    private Double grade;

    private String feedback;
}
