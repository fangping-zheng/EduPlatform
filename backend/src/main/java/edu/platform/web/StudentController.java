package edu.platform.web;

import edu.platform.security.CurrentUsers;
import edu.platform.service.AssignmentService;
import edu.platform.service.CourseService;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.KnowledgeVO;
import edu.platform.service.vo.StudentAssignmentVO;
import edu.platform.web.dto.ApiResponses.SelectResponse;
import edu.platform.web.dto.ApiResponses.SubIdResponse;
import edu.platform.web.dto.SubmitRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学生端接口。URL 层已限定 role=student（见 SecurityConfig）。 */
@RestController
@RequestMapping("/api/student")
public class StudentController {

    private final CourseService courseService;
    private final AssignmentService assignmentService;

    public StudentController(CourseService courseService, AssignmentService assignmentService) {
        this.courseService = courseService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/courses")
    public List<CourseVO> courses() {
        return courseService.listAllCourses(CurrentUsers.require());
    }

    @GetMapping("/my-courses")
    public List<CourseVO> myCourses() {
        return courseService.listMyCourses(CurrentUsers.require());
    }

    @PostMapping("/courses/{courseId}/select")
    public SelectResponse select(@PathVariable String courseId) {
        CourseService.SelectResult r = courseService.selectCourse(CurrentUsers.require(), courseId);
        return new SelectResponse(r.enrolled(), r.selectedCount());
    }

    @DeleteMapping("/courses/{courseId}/select")
    public SelectResponse drop(@PathVariable String courseId) {
        CourseService.SelectResult r = courseService.dropCourse(CurrentUsers.require(), courseId);
        return new SelectResponse(r.enrolled(), r.selectedCount());
    }

    @GetMapping("/assignments")
    public List<StudentAssignmentVO> assignments() {
        return assignmentService.listMyAssignments(CurrentUsers.require());
    }

    @PostMapping("/assignments/{assignId}/submit")
    public SubIdResponse submit(@PathVariable Long assignId, @Valid @RequestBody SubmitRequest request) {
        return new SubIdResponse(assignmentService.submit(CurrentUsers.require(), assignId, request.content()));
    }

    @GetMapping("/courses/{courseId}/knowledge")
    public List<KnowledgeVO> knowledge(@PathVariable String courseId) {
        return courseService.knowledgeTree(courseId);
    }
}
