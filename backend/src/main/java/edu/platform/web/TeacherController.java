package edu.platform.web;

import edu.platform.security.AuthUser;
import edu.platform.security.CurrentUsers;
import edu.platform.service.AssignmentService;
import edu.platform.service.CourseService;
import edu.platform.service.vo.AssignmentVO;
import edu.platform.service.vo.CourseStatsVO;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.StudentGradeVO;
import edu.platform.service.vo.SubmissionVO;
import edu.platform.web.dto.ApiResponses.AssignIdResponse;
import edu.platform.web.dto.ApiResponses.GradeResponse;
import edu.platform.web.dto.ApiResponses.StatusResponse;
import edu.platform.web.dto.ApiResponses.SubmissionGradeResponse;
import edu.platform.web.dto.CourseGradeRequest;
import edu.platform.web.dto.CreateAssignmentRequest;
import edu.platform.web.dto.SubmissionGradeRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师端接口。URL 层已限定 role=teacher；
 * 每次访问具体课程前还会校验「课程是否属于我」（403 NOT_COURSE_OWNER），
 * 因此教师无法通过改 URL 看到同事的课程数据。
 */
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {

    private final CourseService courseService;
    private final AssignmentService assignmentService;

    public TeacherController(CourseService courseService, AssignmentService assignmentService) {
        this.courseService = courseService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/courses")
    public List<CourseVO> myCourses() {
        return courseService.listMyCourses(CurrentUsers.require());
    }

    @GetMapping("/courses/{courseId}/students")
    public List<StudentGradeVO> students(@PathVariable String courseId) {
        courseService.verifyTeacherOwns(courseId, CurrentUsers.require());
        return courseService.listStudents(courseId);
    }

    @GetMapping("/courses/{courseId}/assignments")
    public List<AssignmentVO> assignments(@PathVariable String courseId) {
        courseService.verifyTeacherOwns(courseId, CurrentUsers.require());
        return assignmentService.listByCourse(courseId);
    }

    @PostMapping("/courses/{courseId}/assignments")
    public AssignIdResponse createAssignment(@PathVariable String courseId,
            @Valid @RequestBody CreateAssignmentRequest request) {
        AuthUser me = CurrentUsers.require();
        Long assignId = assignmentService.createAssignment(courseId, request.title(), request.description(),
                request.deadline(), me);
        return new AssignIdResponse(assignId);
    }

    @DeleteMapping("/assignments/{assignId}")
    public StatusResponse revoke(@PathVariable Long assignId) {
        assignmentService.revoke(assignId, CurrentUsers.require());
        return new StatusResponse(0);
    }

    @GetMapping("/assignments/{assignId}/submissions")
    public List<SubmissionVO> submissions(@PathVariable Long assignId) {
        return assignmentService.listSubmissions(assignId, CurrentUsers.require());
    }

    @PostMapping("/submissions/{subId}/grade")
    public SubmissionGradeResponse grade(@PathVariable Long subId, @RequestBody SubmissionGradeRequest request) {
        assignmentService.grade(subId, request.grade(), request.feedback(), CurrentUsers.require());
        return new SubmissionGradeResponse(subId, request.grade(), request.feedback());
    }

    /** 学情完成度统计（已消除 N+1，返回真实 queryCount）。 */
    @GetMapping("/courses/{courseId}/stats")
    public CourseStatsVO stats(@PathVariable String courseId) {
        return courseService.getCourseHomeworkStats(CurrentUsers.require(), courseId);
    }

    @GetMapping("/courses/{courseId}/grades")
    public List<StudentGradeVO> grades(@PathVariable String courseId) {
        courseService.verifyTeacherOwns(courseId, CurrentUsers.require());
        return courseService.listGrades(courseId);
    }

    @PostMapping("/courses/{courseId}/grades")
    public GradeResponse updateGrade(@PathVariable String courseId, @RequestBody CourseGradeRequest request) {
        courseService.verifyTeacherOwns(courseId, CurrentUsers.require());
        courseService.updateGrade(courseId, request.studentId(), request.grade());
        return new GradeResponse(request.studentId(), request.grade());
    }
}
