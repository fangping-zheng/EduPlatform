package edu.platform.service;

import edu.platform.config.SqlCountInspector;
import edu.platform.domain.Course;
import edu.platform.domain.CourseSelection;
import edu.platform.domain.KnowledgeNode;
import edu.platform.domain.User;
import edu.platform.repo.CourseRepository;
import edu.platform.repo.CourseSelectionRepository;
import edu.platform.repo.KnowledgeNodeRepository;
import edu.platform.repo.UserRepository;
import edu.platform.security.AuthUser;
import edu.platform.service.vo.CourseStatsVO;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.GradeDistributionVO;
import edu.platform.service.vo.KnowledgeVO;
import edu.platform.service.vo.StudentGradeVO;
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

/** 课程、选课、成绩与知识点的业务逻辑。 */
@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final CourseSelectionRepository selectionRepository;
    private final KnowledgeNodeRepository knowledgeNodeRepository;
    private final UserRepository userRepository;

    public CourseService(
            CourseRepository courseRepository,
            CourseSelectionRepository selectionRepository,
            KnowledgeNodeRepository knowledgeNodeRepository,
            UserRepository userRepository) {
        this.courseRepository = courseRepository;
        this.selectionRepository = selectionRepository;
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.userRepository = userRepository;
    }

    // ------------------------------------------------------------------ 查询

    /** 学生视角：全部可选课程，带 enrolled / grade。 */
    public List<CourseVO> listAllCourses(AuthUser me) {
        requireRole(me, "student");
        String studentId = requireStudentId(me);
        List<CourseSelection> mine = selectionRepository.findByStudentId(studentId);
        Map<String, Double> myGrades = new HashMap<>();
        mine.forEach(sel -> myGrades.put(sel.getCourseId(), sel.getGrade()));
        Set<String> enrolledIds = mine.stream().map(CourseSelection::getCourseId).collect(Collectors.toSet());
        Map<String, String> teacherNames = teacherNames(courseRepository.findAll());
        return courseRepository.findAll().stream()
                .filter(c -> !isBenchCourse(c.getCourseId()))
                .sorted(Comparator.comparing(Course::getCourseId))
                .map(c -> CourseVO.of(c, teacherNames.get(c.getTeacherId()),
                        myGrades.get(c.getCourseId()), enrolledIds.contains(c.getCourseId())))
                .toList();
    }

    /**
     * 「我的课程」：学生返回已选课程（含成绩），教师返回自己名下的课程。
     *
     * <p>只查两次库：一次拿课程，一次拿教师姓名映射，绝不逐门课去查教师名。
     */
    public List<CourseVO> listMyCourses(AuthUser me) {
        return switch (me.role() == null ? "" : me.role()) {
            case "student" -> {
                String studentId = requireStudentId(me);
                List<CourseSelection> mine = selectionRepository.findByStudentId(studentId);
                Map<String, Double> myGrades = new HashMap<>();
                mine.forEach(sel -> myGrades.put(sel.getCourseId(), sel.getGrade()));
                List<Course> courses = mine.isEmpty()
                        ? List.of()
                        : mine.stream()
                                .map(s -> courseRepository.findById(s.getCourseId()).orElse(null))
                                .filter(Objects::nonNull)
                                .filter(c -> !isBenchCourse(c.getCourseId()))
                                .toList();
                Map<String, String> teacherNames = teacherNames(courses);
                yield courses.stream()
                        .filter(c -> !isBenchCourse(c.getCourseId()))
                        .sorted(Comparator.comparing(Course::getCourseId))
                        .map(c -> CourseVO.of(c, teacherNames.get(c.getTeacherId()),
                                myGrades.get(c.getCourseId()), true))
                        .toList();
            }
            case "teacher" -> {
                List<Course> courses = courseRepository.findByTeacherId(me.teacherId());
                Map<String, String> teacherNames = teacherNames(courses);
                yield courses.stream()
                        .filter(c -> !isBenchCourse(c.getCourseId()))
                        .sorted(Comparator.comparing(Course::getCourseId))
                        .map(c -> CourseVO.of(c, teacherNames.get(c.getTeacherId()), null, null))
                        .toList();
            }
            case "admin" -> {
                List<Course> courses = courseRepository.findAll();
                Map<String, String> teacherNames = teacherNames(courses);
                yield courses.stream()
                        .filter(c -> !isBenchCourse(c.getCourseId()))
                        .sorted(Comparator.comparing(Course::getCourseId))
                        .map(c -> CourseVO.of(c, teacherNames.get(c.getTeacherId()), null, null))
                        .toList();
            }
            default -> throw new ForbiddenException("FORBIDDEN", "无权访问该资源");
        };
    }

    /** 基准测试的隔离数据集（BENCH- 前缀）不对外可见，避免污染业务列表。 */
    private static boolean isBenchCourse(String courseId) {
        return courseId != null && courseId.startsWith("BENCH-");
    }

    public CourseVO getCourse(String courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> notFoundCourse(courseId));
        return CourseVO.of(course, teacherNames(List.of(course)).get(course.getTeacherId()), null, null);
    }

    public List<StudentGradeVO> listStudents(String courseId) {
        return listGrades(courseId);
    }

    /** 课程学生名单 + 成绩（2 条 SQL：选课记录 + 学生资料批量查询）。 */
    public List<StudentGradeVO> listGrades(String courseId) {
        Course course = courseRepository.findById(courseId).orElseThrow(() -> notFoundCourse(courseId));
        List<CourseSelection> selections = selectionRepository.findByCourseId(course.getCourseId());
        Map<String, User> students = studentIndex(selections.stream().map(CourseSelection::getStudentId).toList());
        return selections.stream()
                .sorted(Comparator.comparing(CourseSelection::getStudentId))
                .map(s -> {
                    User u = students.get(s.getStudentId());
                    return new StudentGradeVO(s.getStudentId(),
                            u == null ? null : u.getRealName(),
                            u == null ? null : u.getMajor(),
                            s.getGrade());
                })
                .toList();
    }

    /** 知识点树：一次全量查询后在内存里按 parentId 拼树。 */
    public List<KnowledgeVO> knowledgeTree(String courseId) {
        List<KnowledgeNode> nodes = knowledgeNodeRepository.findByCourseIdOrderByOrderNumAsc(courseId);
        Map<Long, List<KnowledgeNode>> byParent = new LinkedHashMap<>();
        for (KnowledgeNode n : nodes) {
            byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
        }
        return buildChildren(null, byParent);
    }

    private List<KnowledgeVO> buildChildren(Long parentId, Map<Long, List<KnowledgeNode>> byParent) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .map(n -> new KnowledgeVO(n.getNodeId(), n.getCourseId(), n.getParentId(),
                        n.getNodeName(), n.getContent(), n.getOrderNum(),
                        buildChildren(n.getNodeId(), byParent)))
                .toList();
    }

    // ------------------------------------------------------------------ 并发选课（项目亮点 1）

    /**
     * 选课：悲观写锁 + 单事务。
     *
     * <p>顺序很重要 —— 先 {@code SELECT ... FOR UPDATE} 锁住课程行，再在锁内做
     * 「查重 → 校验容量 → 插入选课记录 → selected+1」。
     * 若把容量校验放在锁外，50 个并发请求会同时读到 {@code selected=19 < capacity=20}，
     * 于是全部插入成功，最终 50 人抢到 20 个名额（超卖）。
     *
     * <p>并发压测证据见 GET /api/benchmark/select 与 ConcurrentSelectTest。
     */
    @Transactional
    public SelectResult selectCourse(AuthUser me, String courseId) {
        requireRole(me, "student");
        String studentId = requireStudentId(me);   // 权限边界：学号只来自 token

        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> notFoundCourse(courseId));

        if (selectionRepository.existsByStudentIdAndCourseId(studentId, courseId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SELECTED", "你已选过该课程");
        }
        Integer capacity = course.getCapacity();
        int selected = course.getSelected() == null ? 0 : course.getSelected();
        if (capacity != null && capacity > 0 && selected >= capacity) {
            throw new ApiException(HttpStatus.CONFLICT, "COURSE_FULL", "课程已满");
        }

        CourseSelection selection = new CourseSelection();
        selection.setStudentId(studentId);
        selection.setCourseId(courseId);
        selection.setSelectTime(LocalDateTime.now());
        selectionRepository.save(selection);

        course.setSelected(selected + 1);   // 托管实体，事务提交时自动 UPDATE
        return new SelectResult(true, selected + 1);
    }

    /** 退课：同样在课程行锁内完成「删除选课记录 + selected-1」。 */
    @Transactional
    public SelectResult dropCourse(AuthUser me, String courseId) {
        requireRole(me, "student");
        String studentId = requireStudentId(me);

        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> notFoundCourse(courseId));

        CourseSelection selection = selectionRepository.findByStudentIdAndCourseId(studentId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOT_SELECTED", "你未选过该课程"));
        selectionRepository.delete(selection);

        int selected = course.getSelected() == null ? 0 : course.getSelected();
        course.setSelected(Math.max(0, selected - 1));   // 计数不允许减成负数
        return new SelectResult(false, course.getSelected());
    }

    // ------------------------------------------------------------------ 成绩

    @Transactional
    public void updateGrade(String courseId, String studentId, Double grade) {
        if (grade != null && (grade < 0 || grade > 100)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GRADE", "成绩需在 0-100 之间");
        }
        CourseSelection selection = selectionRepository.findByStudentIdAndCourseId(studentId, courseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SELECTION_NOT_FOUND", "该学生未选此课程"));
        selection.setGrade(grade);
        selectionRepository.save(selection);
    }

    // ------------------------------------------------------------------ 学情统计（项目亮点 2）

    /**
     * 学情完成度统计。
     *
     * <p>只打 2 条 SQL：① 校验课程归属（同时拿到课程）；② 一条 group by 聚合查询，
     * 一次拿到「每个学生的应交作业数 + 已交作业数」。朴素写法是「先查学生名单，
     * 再对每个学生、每次作业单独 count」，30 人 × 4 次作业 = 120+ 条。
     *
     * <p>{@code queryCount} 用 SqlCountInspector 实测，随响应返回，供前端直接展示。
     */
    public CourseStatsVO getCourseHomeworkStats(AuthUser me, String courseId) {
        SqlCountInspector.reset();
        Course course = courseRepository.findById(courseId).orElseThrow(() -> notFoundCourse(courseId));
        verifyTeacherOwns(course, me);

        List<Object[]> rows = selectionRepository.studentHomeworkStats(course.getCourseId());
        int queryCount = SqlCountInspector.count();

        List<CourseStatsVO.StudentStatVO> students = rows.stream()
                .map(r -> {
                    String studentId = String.valueOf(r[0]);
                    String realName = r[1] == null ? null : String.valueOf(r[1]);
                    int total = ((Number) r[2]).intValue();
                    int submitted = ((Number) r[3]).intValue();
                    return new CourseStatsVO.StudentStatVO(studentId, realName, total, submitted,
                            Math.max(0, total - submitted));
                })
                .toList();
        int totalAssignments = students.isEmpty() ? 0 : students.get(0).totalAssignments();
        return new CourseStatsVO(course.getCourseId(), totalAssignments, students.size(), queryCount, students);
    }

    /** 成绩分段分布（教师工具 getCourseGradeDistribution）。 */
    public GradeDistributionVO getCourseGradeDistribution(AuthUser me, String courseId) {
        Course course = courseRepository.findById(courseId).orElseThrow(() -> notFoundCourse(courseId));
        verifyTeacherOwns(course, me);
        List<Double> grades = listGrades(courseId).stream()
                .map(StudentGradeVO::grade)
                .filter(Objects::nonNull)
                .toList();
        int[] counts = new int[5];
        for (Double g : grades) {
            if (g < 60) {
                counts[0]++;
            } else if (g < 70) {
                counts[1]++;
            } else if (g < 80) {
                counts[2]++;
            } else if (g < 90) {
                counts[3]++;
            } else {
                counts[4]++;
            }
        }
        Double average = grades.isEmpty() ? null
                : Math.round(grades.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
        return new GradeDistributionVO(
                courseId,
                selectionRepository.findByCourseId(courseId).size(),
                grades.size(),
                average,
                grades.stream().max(Double::compareTo).orElse(null),
                grades.stream().min(Double::compareTo).orElse(null),
                List.of(
                        new GradeDistributionVO.Bucket("<60", counts[0]),
                        new GradeDistributionVO.Bucket("60-69", counts[1]),
                        new GradeDistributionVO.Bucket("70-79", counts[2]),
                        new GradeDistributionVO.Bucket("80-89", counts[3]),
                        new GradeDistributionVO.Bucket("90-100", counts[4])));
    }

    // ------------------------------------------------------------------ 内部工具

    /** 教师只能操作自己名下的课程；管理员放行。控制器与 Agent 工具都调用它。 */
    public void verifyTeacherOwns(Course course, AuthUser me) {
        if (me == null) {
            throw new ForbiddenException("FORBIDDEN", "无权访问该资源");
        }
        if ("admin".equals(me.role())) {
            return;
        }
        if (!"teacher".equals(me.role()) || !Objects.equals(course.getTeacherId(), me.teacherId())) {
            throw new ForbiddenException("NOT_COURSE_OWNER", "只能操作自己名下的课程");
        }
    }

    public void verifyTeacherOwns(String courseId, AuthUser me) {
        verifyTeacherOwns(courseRepository.findById(courseId).orElseThrow(() -> notFoundCourse(courseId)), me);
    }

    private void requireRole(AuthUser me, String role) {
        if (me == null || !role.equals(me.role())) {
            throw new ForbiddenException("FORBIDDEN", "无权访问该资源");
        }
    }

    private String requireStudentId(AuthUser me) {
        if (me.studentId() == null || me.studentId().isBlank()) {
            throw new ForbiddenException("NO_STUDENT_ID", "当前账号缺少学号信息");
        }
        return me.studentId();
    }

    private ApiException notFoundCourse(String courseId) {
        return new ApiException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在：" + courseId);
    }

    private Map<String, String> teacherNames(List<Course> courses) {
        Set<String> teacherIds = courses.stream()
                .map(Course::getTeacherId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (teacherIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByTeacherIdIn(teacherIds).stream()
                .collect(Collectors.toMap(User::getTeacherId, u -> u.getRealName() == null ? "" : u.getRealName(),
                        (a, b) -> a));
    }

    private Map<String, User> studentIndex(List<String> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByStudentIdIn(studentIds).stream()
                .filter(u -> u.getStudentId() != null)
                .collect(Collectors.toMap(User::getStudentId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    public record SelectResult(boolean enrolled, int selectedCount) {
    }
}
