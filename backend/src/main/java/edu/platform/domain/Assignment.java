package edu.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 作业。status=1 进行中，status=0 已撤回（撤回后不允许再提交）。 */
@Getter
@Setter
@Entity
@Table(name = "assignment")
public class Assignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long assignId;

    private String courseId;

    private String title;

    private String description;

    private LocalDateTime deadline;

    private Integer status;

    private LocalDateTime publishTime;
}
