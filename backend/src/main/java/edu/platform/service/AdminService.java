package edu.platform.service;

import edu.platform.domain.Course;
import edu.platform.domain.User;
import edu.platform.repo.AssignmentRepository;
import edu.platform.repo.CourseRepository;
import edu.platform.repo.CourseSelectionRepository;
import edu.platform.repo.KnowledgeNodeRepository;
import edu.platform.repo.SubmissionRepository;
import edu.platform.repo.UserRepository;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.PlatformOverviewVO;
import edu.platform.service.vo.UserVO;
import edu.platform.web.ApiException;
import edu.platform.web.dto.CourseForm;
import edu.platform.web.dto.UserForm;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员：用户与课程的增删改查、平台总览。 */
@Service
public class AdminService {

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseSelectionRepository selectionRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final KnowledgeNodeRepository knowledgeNodeRepository;

    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AdminService(
            UserRepository userRepository,
            CourseRepository courseRepository,
            CourseSelectionRepository selectionRepository,
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            KnowledgeNodeRepository knowledgeNodeRepository,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.selectionRepository = selectionRepository;
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ------------------------------------------------------------------ 用户

    public List<UserVO> listUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(AdminService::toVO)
                .toList();
    }

    public List<UserVO> listUsersByRole(String role) {
        List<User> users = (role == null || role.isBlank())
                ? userRepository.findAll()
                : userRepository.findByRole(role);
        return users.stream().sorted(Comparator.comparing(User::getId)).map(AdminService::toVO).toList();
    }

    @Transactional
    public Long createUser(UserForm form) {
        if (form.username() == null || form.username().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USERNAME", "用户名不能为空");
        }
        if (userRepository.existsByUsername(form.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        User user = new User();
        user.setUsername(form.username());
        user.setPassword(passwordEncoder.encode(
                form.password() == null || form.password().isBlank() ? "123456" : form.password()));
        apply(user, form);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user).getId();
    }

    /** 更新用户；{@code password} 为空字符串表示不修改密码。 */
    @Transactional
    public void updateUser(Long id, UserForm form) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (form.password() != null && !form.password().isBlank()) {
            user.setPassword(passwordEncoder.encode(form.password()));
        }
        user.setRealName(form.realName() == null ? user.getRealName() : form.realName());
        apply(user, new UserForm(
                user.getUsername(),
                null,
                form.realName(),
                form.role(),
                form.studentId(),
                form.teacherId(),
                form.major(),
                form.title()));
        userRepository.save(user);
    }

    /** 删除用户，并清理其选课与提交，避免留下孤儿数据。 */
    @Transactional
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
        if (user.getStudentId() != null) {
            selectionRepository.deleteAllByStudentId(user.getStudentId());
            submissionRepository.deleteAllByStudentId(user.getStudentId());
        }
        userRepository.delete(user);
    }

    // ------------------------------------------------------------------ 课程

    public List<CourseVO> listCourses() {
        List<Course> courses = courseRepository.findAll();
        return courses.stream()
                // 基准测试的隔离课程（BENCH- 前缀）不对外可见
                .filter(c -> c.getCourseId() == null || !c.getCourseId().startsWith("BENCH-"))
                .sorted(Comparator.comparing(Course::getCourseId))
                .map(c -> CourseVO.of(c, teacherNames(courses).get(c.getTeacherId()), null, null))
                .toList();
    }

    @Transactional
    public String createCourse(CourseForm form) {
        if (form.courseId() == null || form.courseId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COURSE_ID", "课程号不能为空");
        }
        if (courseRepository.existsById(form.courseId())) {
            throw new ApiException(HttpStatus.CONFLICT, "COURSE_EXISTS", "课程号已存在");
        }
        Course course = new Course();
        course.setCourseId(form.courseId());
        apply(course, form);
        course.setSelected(0);
        return courseRepository.save(course).getCourseId();
    }

    @Transactional
    public void updateCourse(String courseId, CourseForm form) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在"));
        apply(course, form);
        courseRepository.save(course);
    }

    /** 删除课程：连带清理选课、作业、提交、知识点。 */
    @Transactional
    public void deleteCourse(String courseId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在");
        }
        submissionRepository.deleteAllByCourseId(courseId);
        assignmentRepository.deleteAllByCourseId(courseId);
        selectionRepository.deleteAllByCourseId(courseId);
        knowledgeNodeRepository.deleteAllByCourseId(courseId);
        courseRepository.deleteById(courseId);
    }

    // ------------------------------------------------------------------ 总览

    public PlatformOverviewVO overview() {
        return new PlatformOverviewVO(
                userRepository.count(),
                userRepository.countByRole("admin"),
                userRepository.countByRole("teacher"),
                userRepository.countByRole("student"),
                courseRepository.count(),
                selectionRepository.count(),
                assignmentRepository.count(),
                submissionRepository.count());
    }

    // ------------------------------------------------------------------ 内部工具

    private void apply(User user, UserForm form) {
        if (form.realName() != null) {
            user.setRealName(form.realName());
        }
        if (form.role() != null && !form.role().isBlank()) {
            user.setRole(form.role());
        }
        user.setStudentId(form.studentId());
        user.setTeacherId(form.teacherId());
        user.setMajor(form.major());
        user.setTitle(form.title());
    }

    private void apply(Course course, CourseForm form) {
        if (form.name() != null) {
            course.setName(form.name());
        }
        if (form.credit() != null) {
            course.setCredit(form.credit());
        }
        course.setDescription(form.description());
        if (form.teacherId() != null) {
            if (!form.teacherId().isBlank() && userRepository.findByTeacherId(form.teacherId()).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TEACHER_NOT_FOUND", "指定教师工号不存在");
            }
            course.setTeacherId(form.teacherId());
        }
        if (form.capacity() != null) {
            course.setCapacity(form.capacity());
        }
        if (course.getCredit() == null) {
            course.setCredit(0);
        }
        if (course.getCapacity() == null) {
            course.setCapacity(0);
        }
    }

    private java.util.Map<String, String> teacherNames(List<Course> courses) {
        Set<String> ids = courses.stream().map(Course::getTeacherId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return userRepository.findByTeacherIdIn(ids).stream()
                .collect(Collectors.toMap(User::getTeacherId,
                        u -> u.getRealName() == null ? "" : u.getRealName(), (a, b) -> a));
    }

    public static UserVO toVO(User u) {
        return new UserVO(u.getId(), u.getUsername(), u.getRealName(), u.getRole(),
                u.getStudentId(), u.getTeacherId(), u.getMajor(), u.getTitle());
    }
}
