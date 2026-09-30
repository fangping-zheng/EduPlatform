package edu.platform.web;

import edu.platform.service.AdminService;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.UserVO;
import edu.platform.web.dto.ApiResponses.CourseIdResponse;
import edu.platform.web.dto.ApiResponses.DeletedResponse;
import edu.platform.web.dto.ApiResponses.IdResponse;
import edu.platform.web.dto.CourseForm;
import edu.platform.web.dto.UserForm;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员端接口（URL 层限定 role=admin）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public List<UserVO> users() {
        return adminService.listUsers();
    }

    @PostMapping("/users")
    public IdResponse createUser(@RequestBody UserForm form) {
        return new IdResponse(adminService.createUser(form));
    }

    @PutMapping("/users/{id}")
    public IdResponse updateUser(@PathVariable Long id, @RequestBody UserForm form) {
        adminService.updateUser(id, form);
        return new IdResponse(id);
    }

    @DeleteMapping("/users/{id}")
    public DeletedResponse deleteUser(@PathVariable Long id) {
        adminService.deleteUser(id);
        return new DeletedResponse(true);
    }

    @GetMapping("/courses")
    public List<CourseVO> courses() {
        return adminService.listCourses();
    }

    @PostMapping("/courses")
    public CourseIdResponse createCourse(@RequestBody CourseForm form) {
        return new CourseIdResponse(adminService.createCourse(form));
    }

    @PutMapping("/courses/{courseId}")
    public CourseIdResponse updateCourse(@PathVariable String courseId, @RequestBody CourseForm form) {
        adminService.updateCourse(courseId, form);
        return new CourseIdResponse(courseId);
    }

    @DeleteMapping("/courses/{courseId}")
    public DeletedResponse deleteCourse(@PathVariable String courseId) {
        adminService.deleteCourse(courseId);
        return new DeletedResponse(true);
    }
}
