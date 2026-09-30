package edu.platform.service;

import edu.platform.domain.Assignment;
import edu.platform.domain.Course;
import edu.platform.domain.CourseSelection;
import edu.platform.domain.Submission;
import edu.platform.domain.User;
import edu.platform.repo.AssignmentRepository;
import edu.platform.repo.CourseRepository;
import edu.platform.repo.CourseSelectionRepository;
import edu.platform.repo.SubmissionRepository;
import edu.platform.repo.UserRepository;
import edu.platform.security.AuthUser;
import edu.platform.service.vo.AssignmentVO;
import edu.platform.service.vo.StudentAssignmentVO;
import edu.platform.service.vo.SubmissionVO;
import edu.platform.web.ApiException;
import edu.platform.web.ForbiddenException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 作业、提交与成绩评阅。所有教师侧写操作都校验「课程是否属于我」。 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final CourseSelectionRepository selectionRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private final CourseService courseService;

    public AssignmentService(
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            CourseSelectionRepository selectionRepository,
            CourseRepository courseRepository,
            UserRepository userRepository,
            CourseService courseService) {
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.selectionRepository = selectionRepository;
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
        this.courseService = courseService;
    }

    // ------------------------------------------------------------------ 学生侧

    /**
     * 当前学生所有课程的作业，含 submitted / submission。
     *
     * <p>只有 3 条 SQL：课程、作业、我的提交（批量 IN 查询），不会按作业逐条查提交。
     * 已撤回（status=0）的作业同样返回，前端会标成「已撤回，无法提交」。
     */
    public List<StudentAssignmentVO> listMyAssignments(AuthUser me) {
        return buildAssignments(me, myCourses(me), assignment -> true);
    }

    /** 我未提交的作业（只统计进行中的作业，撤回的不算欠交）。 */
    public List<StudentAssignmentVO> listMyUnsubmitted(AuthUser me) {
        return buildAssignments(me, myCourses(me), a -> !Objects.equals(a.getStatus(), 0)).stream()
                .filter(v -> Boolean.FALSE.equals(v.submitted()))
                .toList();
    }

    /** 未来 N 天内截止的作业（含已提交的，用于提醒）。 */
    public List<StudentAssignmentVO> listUpcoming(AuthUser me, int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime until = now.plusDays(Math.max(0, days));
        return buildAssignments(me, myCourses(me),
                a -> !Objects.equals(a.getStatus(), 0)
                        && a.getDeadline() != null
                        && !a.getDeadline().isBefore(now)
                        && !a.getDeadline().isAfter(until));
    }

    /** 提交作业：已撤回 / 已过截止时间一律 409 ASSIGNMENT_CLOSED；重复提交覆盖原记录并清空旧评分。 */
    @Transactional
    public Long submit(AuthUser me, Long assignId, String content) {
        if (me == null || !"student".equals(me.role()) || me.studentId() == null) {
            throw new ForbiddenException("FORBIDDEN", "只有学生可以提交作业");
        }
        Assignment assignment = requireAssignment(assignId);
        if (Objects.equals(assignment.getStatus(), 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_CLOSED", "作业已撤回，无法提交");
        }
        if (assignment.getDeadline() != null && assignment.getDeadline().isBefore(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSIGNMENT_CLOSED", "作业已过截止时间，无法提交");
        }
        if (!selectionRepository.existsByStudentIdAndCourseId(me.studentId(), assignment.getCourseId())) {
            throw new ForbiddenException("NOT_ENROLLED", "你未选修该课程，无法提交作业");
        }

        Submission submission = submissionRepository
                .findByAssignIdAndStudentId(assignId, me.studentId())
                .orElseGet(Submission::new);
        submission.setAssignId(assignId);
        submission.setStudentId(me.studentId());
        submission.setContent(content);
        submission.setSubmitTime(LocalDateTime.now());
        // 重新提交视为新一轮，旧的评分与评语作废，避免教师看到过期反馈
        submission.setGrade(null);
        submission.setFeedback(null);
        return submissionRepository.save(submission).getSubId();
    }

    // ------------------------------------------------------------------ 教师侧

    /**
     * 某课程的作业列表。
     *
     * <p>调用方（控制器 / Agent 工具）必须先校验课程归属：{@link CourseService#verifyTeacherOwns(String, AuthUser)}。
     */
    public List<AssignmentVO> listByCourse(String courseId) {
        return assignmentRepository.findByCourseId(courseId).stream()
                .sorted(Comparator.comparing(Assignment::getAssignId))
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public Long createAssignment(String courseId, String title, String description, LocalDateTime deadline,
            AuthUser me) {
        courseService.verifyTeacherOwns(courseId, me);
        Assignment assignment = new Assignment();
        assignment.setCourseId(courseId);
        assignment.setTitle(title);
        assignment.setDescription(description);
        assignment.setDeadline(deadline);
        assignment.setStatus(1);
        assignment.setPublishTime(LocalDateTime.now());
        return assignmentRepository.save(assignment).getAssignId();
    }

    /** 撤回作业（status=0）。撤回后学生不能再提交。 */
    @Transactional
    public void revoke(Long assignId, AuthUser me) {
        Assignment assignment = requireAssignment(assignId);
        courseService.verifyTeacherOwns(assignment.getCourseId(), me);
        assignment.setStatus(0);
        assignmentRepository.save(assignment);
    }

    /** 某次作业的提交列表（已交）。 */
    public List<SubmissionVO> listSubmissions(Long assignId, AuthUser me) {
        Assignment assignment = requireAssignment(assignId);
        courseService.verifyTeacherOwns(assignment.getCourseId(), me);
        return submissionRows(assignId);
    }

    /**
     * 某次作业的提交概况：应交人数、已交名单（含成绩）、未交名单。
     *
     * <p>供教师端 Agent 工具 getAssignmentSubmissions 使用，仍然校验课程归属。
     */
    public SubmissionOverview submissionOverview(Long assignId, AuthUser me) {
        if (assignId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_ASSIGN_ID", "缺少作业编号 assignId");
        }
        Assignment assignment = requireAssignment(assignId);
        courseService.verifyTeacherOwns(assignment.getCourseId(), me);
        Course course = courseRepository.findById(assignment.getCourseId()).orElse(null);
        List<SubmissionVO> submissions = submissionRows(assignId);
        Set<String> submittedIds = submissions.stream().map(SubmissionVO::studentId).collect(Collectors.toSet());

        List<CourseSelection> selections = selectionRepository.findByCourseId(assignment.getCourseId());
        Map<String, User> students = studentIndex(selections.stream().map(CourseSelection::getStudentId).toList());
        List<Map<String, Object>> unsubmitted = new ArrayList<>();
        for (CourseSelection selection : selections) {
            if (submittedIds.contains(selection.getStudentId())) {
                continue;
            }
            User u = students.get(selection.getStudentId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", selection.getStudentId());
            row.put("realName", u == null ? null : u.getRealName());
            unsubmitted.add(row);
        }
        return new SubmissionOverview(
                assignment.getAssignId(),
                assignment.getCourseId(),
                course == null ? null : course.getName(),
                assignment.getTitle(),
                assignment.getDeadline() == null ? null : assignment.getDeadline().toString(),
                0,
                selections.size(),
                submissions.size(),
                unsubmitted.size(),
                submissions,
                unsubmitted);
    }

    @Transactional
    public void grade(Long subId, Double grade, String feedback, AuthUser me) {
        if (grade != null && (grade < 0 || grade > 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "成绩需在 0-100 之间");
        }
        Submission submission = submissionRepository.findById(subId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SUBMISSION_NOT_FOUND", "提交记录不存在"));
        Assignment assignment = requireAssignment(submission.getAssignId());
        courseService.verifyTeacherOwns(assignment.getCourseId(), me);
        submission.setGrade(grade);
        submission.setFeedback(feedback);
        submissionRepository.save(submission);
    }

    // ------------------------------------------------------------------ 内部工具

    private List<SubmissionVO> submissionRows(Long assignId) {
        List<Submission> submissions = submissionRepository.findByAssignId(assignId);
        Map<String, User> students = studentIndex(submissions.stream().map(Submission::getStudentId).toList());
        return submissions.stream()
                .sorted(Comparator.comparing(Submission::getStudentId))
                .map(s -> new SubmissionVO(
                        s.getSubId(),
                        s.getAssignId(),
                        s.getStudentId(),
                        students.containsKey(s.getStudentId()) ? students.get(s.getStudentId()).getRealName() : null,
                        s.getContent(),
                        s.getSubmitTime(),
                        s.getGrade(),
                        s.getFeedback()))
                .toList();
    }

    private List<StudentAssignmentVO> buildAssignments(
            AuthUser me, List<Course> courses, java.util.function.Predicate<Assignment> filter) {
        List<String> courseIds = courses.stream().map(Course::getCourseId).toList();
        if (courseIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> courseNames = courses.stream()
                .collect(Collectors.toMap(Course::getCourseId, Course::getName, (a, b) -> a));
        List<Assignment> assignments = assignmentRepository.findByCourseIdIn(courseIds).stream()
                .filter(filter)
                .sorted(Comparator.comparing(Assignment::getDeadline,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        Map<Long, Submission> mySubmissions = Map.of();
        if (me != null && "student".equals(me.role()) && me.studentId() != null && !assignments.isEmpty()) {
            List<Long> assignIds = assignments.stream().map(Assignment::getAssignId).toList();
            mySubmissions = submissionRepository.findByStudentIdAndAssignIdIn(me.studentId(), assignIds).stream()
                    .collect(Collectors.toMap(Submission::getAssignId, Function.identity(), (a, b) -> a));
        }
        Map<Long, Submission> mine = mySubmissions;

        return assignments.stream().map(a -> {
            Submission s = mine.get(a.getAssignId());
            return new StudentAssignmentVO(
                    a.getAssignId(),
                    a.getCourseId(),
                    courseNames.get(a.getCourseId()),
                    a.getTitle(),
                    a.getDescription(),
                    a.getDeadline(),
                    a.getStatus(),
                    a.getPublishTime(),
                    s != null,
                    s == null ? null
                            : new StudentAssignmentVO.MySubmissionVO(
                                    s.getSubId(), s.getContent(), s.getSubmitTime(), s.getGrade(), s.getFeedback()));
        }).toList();
    }

    /** 学生 → 已选课程；教师 → 自己名下课程；管理员 → 全部课程。 */
    private List<Course> myCourses(AuthUser me) {
        if (me == null) {
            throw new ForbiddenException("FORBIDDEN", "无权访问该资源");
        }
        return switch (me.role() == null ? "" : me.role()) {
            case "student" -> {
                List<String> ids = me.studentId() == null ? List.of()
                        : selectionRepository.findByStudentId(me.studentId()).stream()
                                .map(CourseSelection::getCourseId)
                                .toList();
                yield ids.isEmpty() ? List.of() : courseRepository.findAllById(ids);
            }
            case "teacher" -> courseRepository.findByTeacherId(me.teacherId());
            case "admin" -> courseRepository.findAll();
            default -> throw new ForbiddenException("FORBIDDEN", "无权访问该资源");
        };
    }

    private Assignment requireAssignment(Long assignId) {
        return assignmentRepository.findById(assignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND", "作业不存在"));
    }

    private AssignmentVO toVO(Assignment a) {
        return new AssignmentVO(a.getAssignId(), a.getCourseId(), a.getTitle(), a.getDescription(),
                a.getDeadline(), a.getStatus(), a.getPublishTime());
    }

    private Map<String, User> studentIndex(List<String> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByStudentIdIn(studentIds).stream()
                .filter(u -> u.getStudentId() != null)
                .collect(Collectors.toMap(User::getStudentId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    /** 某次作业的提交概况。 */
    public record SubmissionOverview(
            Long assignId,
            String courseId,
            String courseName,
            String title,
            String deadline,
            int gradeCount,
            int totalStudents,
            int submittedCount,
            int unsubmittedCount,
            List<SubmissionVO> submissions,
            List<Map<String, Object>> unsubmitted) {
    }
}
