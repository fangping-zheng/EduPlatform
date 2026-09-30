package edu.platform.service.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.platform.domain.Course;

/**
 * 课程视图对象（学生端 / 教师端 / 管理员端共用）。
 *
 * <p>字段名与 docs/API.md 严格一致：{@code selectedCount} 是课程已选人数，
 * {@code enrolled} 表示「当前学生是否已选」，{@code grade} 是当前学生在
 * 该课程上的成绩（未出成绩为 null）。
 *
 * <p>{@code enrolled} 为 null 时不序列化（教师/管理员视角不返回该字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CourseVO(
        String courseId,
        String name,
        Integer credit,
        String description,
        String teacherId,
        String teacherName,
        Integer capacity,
        Integer selectedCount,
        Double grade,
        Boolean enrolled) {

    public static CourseVO of(Course course, String teacherName, Double grade, Boolean enrolled) {
        return new CourseVO(
                course.getCourseId(),
                course.getName(),
                course.getCredit(),
                course.getDescription(),
                course.getTeacherId(),
                teacherName,
                course.getCapacity(),
                course.getSelected(),
                grade,
                enrolled);
    }
}
