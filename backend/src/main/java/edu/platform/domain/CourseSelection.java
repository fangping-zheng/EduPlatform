package edu.platform.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 选课记录（含该课程的成绩）。 */
@Getter
@Setter
@Entity
@Table(name = "course_selection")
public class CourseSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String studentId;

    private String courseId;

    /** 0-100，未出成绩时为 null。 */
    private Double grade;

    private LocalDateTime selectTime;
}
