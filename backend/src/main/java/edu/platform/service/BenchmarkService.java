package edu.platform.service;

import edu.platform.config.SqlCountInspector;
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
import edu.platform.web.ApiException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 性能基准服务：为简历提供可复现、可验证的量化数字。
 *
 * <p>两个基准都用「隔离数据集」而不是当前库里的业务数据，因此任何人跑出来的数字都一样：
 * <ul>
 *   <li>{@code stats} —— 30 名学生 × 4 次作业，对比 N+1 实现与 group by 聚合实现的真实 SQL 条数与耗时；</li>
 *   <li>{@code select} —— N 个线程同时抢 M 个名额，验证悲观锁下不会超卖。</li>
 * </ul>
 */
@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    /** 隔离数据集：学情统计对比。 */
    private static final String STATS_COURSE_ID = "BENCH-STATS";
    private static final int FIXTURE_STUDENTS = 30;
    private static final int FIXTURE_ASSIGNMENTS = 4;
    /** 每个实现跑 3 次：queryCount 取最后一次（确定性，三次相同），elapsedMs 取三次平均。 */
    private static final int RUNS = 3;
    private static final int MAX_SELECT_THREADS = 500;

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseSelectionRepository selectionRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final CourseService courseService;
    private final PasswordEncoder passwordEncoder;

    public BenchmarkService(
            UserRepository userRepository,
            CourseRepository courseRepository,
            CourseSelectionRepository selectionRepository,
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            CourseService courseService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.selectionRepository = selectionRepository;
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.courseService = courseService;
        this.passwordEncoder = passwordEncoder;
    }

    // ================================================================== 学情统计基准

    /**
     * 对比 N+1 与聚合实现的真实 SQL 条数 / 耗时。
     *
     * @param requestedCourseId 请求参数原样回显（对比始终跑在隔离数据集 BENCH-STATS 上）
     */
    public StatsBenchmark stats(String requestedCourseId) {
        // 整个基准跑在隔离数据集上，无论成功还是抛异常，都在 finally 里清干净
        try {
            Course course = prepareStatsFixture();

            Measurement naive = run(RUNS, () -> naiveTally(course.getCourseId()));
            Measurement optimized = run(RUNS, () -> optimizedTally(course.getCourseId()));

            double speedup = naive.avgNanos() == 0 ? 0
                    : Math.round((naive.avgNanos() / (double) optimized.avgNanos()) * 10) / 10.0;
            log.info("benchmark/stats naive={} SQL/{}ms optimized={} SQL/{}ms speedup={}x",
                    naive.queryCount(), naive.avgMillis(), optimized.queryCount(), optimized.avgMillis(), speedup);

            return new StatsBenchmark(
                    requestedCourseId == null || requestedCourseId.isBlank() ? STATS_COURSE_ID : requestedCourseId,
                    new Block(naive.queryCount(), naive.avgMillis(), RUNS, naive.rows(), FIXTURE_STUDENTS, FIXTURE_ASSIGNMENTS),
                    new Block(optimized.queryCount(), optimized.avgMillis(), RUNS, optimized.rows(), FIXTURE_STUDENTS, FIXTURE_ASSIGNMENTS),
                    speedup,
                    new Fixture(FIXTURE_STUDENTS, FIXTURE_ASSIGNMENTS, 0.6));
        } finally {
            cleanupStatsFixture();
        }
    }

    /** naive：1 条查学生 + 1 条查作业 + 30×4 = 120 条逐条 count。 */
    /**
     * naive：1 条查「这门课有哪些学生」+ 1 条查作业 + 学生数 × 作业数 条逐条 count。
     *
     * <p>30 学生 × 4 作业时正好是 1 + 1 + 120 = 122 条 —— 这就是优化前的真实代价。
     *
     * <p>学生名单从 course_selection 取（这也是更真实的写法：N+1 的第一步本来就是
     * 「先查这门课有哪些学生」），不依赖 sys_user，因此基准数据不会污染用户表。
     */
    private Tally naiveTally(String courseId) {
        List<String> studentIds = selectionRepository.findByCourseId(courseId).stream()
                .map(CourseSelection::getStudentId)
                .sorted()
                .toList();
        List<Assignment> assignments = assignmentRepository.findByCourseIdAndStatus(courseId, 1);
        int total = 0;
        for (String studentId : studentIds) {
            for (Assignment assignment : assignments) {
                total += (int) submissionRepository.countByAssignIdAndStudentId(
                        assignment.getAssignId(), studentId);
            }
        }
        return new Tally(total, studentIds.size() * assignments.size());
    }

    /** optimized：1 条 group by 聚合 + 1 条作业总数，共 2 条。 */
    /** optimized：1 条 group by 聚合 + 1 条作业总数，共 2 条。 */
    private Tally optimizedTally(String courseId) {
        List<Object[]> rows = submissionRepository.countSubmissionsGroupByStudentForCourse(courseId);
        long assignments = assignmentRepository.countByCourseIdAndStatus(courseId, 1);
        int total = rows.stream().mapToInt(r -> ((Number) r[1]).intValue()).sum();
        return new Tally(total, (int) assignments);
    }

    /** 用 SqlCountInspector 夹住真实 SQL 条数，用 nanoTime 测耗时；重复 RUNS 次。 */
    private Measurement run(int runs, java.util.function.Supplier<Tally> body) {
        long nanos = 0;
        int queries = 0;
        int rows = 0;
        for (int i = 0; i < runs; i++) {
            SqlCountInspector.reset();
            long t0 = System.nanoTime();
            Tally tally = body.get();
            long delta = System.nanoTime() - t0;
            nanos += delta;
            queries = SqlCountInspector.count();
            rows = tally.submittedOrTotal();
        }
        return new Measurement(queries, nanos / runs, rows);
    }

    /** 幂等重建 BENCH-STATS 隔离数据集。 */
    private Course prepareStatsFixture() {
        Course course = courseRepository.findById(STATS_COURSE_ID).orElseGet(() -> {
            Course c = new Course();
            c.setCourseId(STATS_COURSE_ID);
            c.setName("性能基准数据集（N+1 对比）");
            c.setCredit(0);
            c.setCapacity(9999);
            c.setSelected(0);
            c.setTeacherId("T1001");
            c.setDescription("仅用于 /api/benchmark/stats 的可复现数据集，可随时重建。");
            return c;
        });

        // 先删提交（依赖 assignment 的子查询），再删作业，最后删选课
        submissionRepository.deleteAllByCourseId(STATS_COURSE_ID);
        assignmentRepository.deleteAllByCourseId(STATS_COURSE_ID);
        selectionRepository.deleteAllByCourseId(STATS_COURSE_ID);

        List<String> studentIds = fixtureStudentIds();

        List<CourseSelection> selections = new ArrayList<>();
        for (String studentId : studentIds) {
            CourseSelection selection = new CourseSelection();
            selection.setStudentId(studentId);
            selection.setCourseId(STATS_COURSE_ID);
            selection.setSelectTime(LocalDateTime.now());
            selections.add(selection);
        }
        selectionRepository.saveAll(selections);

        List<Assignment> assignments = new ArrayList<>();
        for (int j = 0; j < FIXTURE_ASSIGNMENTS; j++) {
            Assignment a = new Assignment();
            a.setCourseId(STATS_COURSE_ID);
            a.setTitle("基准作业" + (j + 1));
            a.setDescription("基准数据集作业 " + (j + 1));
            a.setDeadline(LocalDateTime.now().plusDays(7L * (j + 1)));
            a.setStatus(1);
            a.setPublishTime(LocalDateTime.now());
            assignments.add(a);
        }
        assignmentRepository.saveAll(assignments);

        // 约 60% 提交率，故意不整齐（不同学生欠交情况不同，才能体现 N+1 的代价）
        List<Submission> submissions = new ArrayList<>();
        for (int i = 0; i < studentIds.size(); i++) {
            for (int j = 0; j < assignments.size(); j++) {
                if ((i * 7 + j * 13) % 10 < 6) {
                    Submission s = new Submission();
                    s.setAssignId(assignments.get(j).getAssignId());
                    s.setStudentId(studentIds.get(i));
                    s.setContent("基准提交 i=" + i + " j=" + j);
                    s.setSubmitTime(LocalDateTime.now());
                    submissions.add(s);
                }
            }
        }
        submissionRepository.saveAll(submissions);

        course.setCapacity(9999);
        course.setSelected(studentIds.size());
        courseRepository.save(course);
        return course;
    }

    /** 跑完立刻清理统计数据集：提交 → 作业 → 选课 → 课程本身。异常也不能漏。 */
    private void cleanupStatsFixture() {
        try {
            submissionRepository.deleteAllByCourseId(STATS_COURSE_ID);
            assignmentRepository.deleteAllByCourseId(STATS_COURSE_ID);
            selectionRepository.deleteAllByCourseId(STATS_COURSE_ID);
            courseRepository.findById(STATS_COURSE_ID).ifPresent(courseRepository::delete);
        } catch (Exception e) {
            log.warn("清理基准数据集 {} 失败：{}", STATS_COURSE_ID, e.getMessage());
        }
    }

    private List<String> fixtureStudentIds() {
        return IntStream.rangeClosed(1, FIXTURE_STUDENTS)
                .mapToObj(i -> String.format("B30%03d", i))
                .toList();
    }

    // ================================================================== 并发选课基准

    /**
     * 并发选课压测：threads 个线程同时抢 capacity 个名额。
     *
     * <p>全部线程真实调用 {@link CourseService#selectCourse}（悲观写锁 + 事务），
     * 因此结论可证伪：如果锁失效，oversold 会 > 0。
     */
    public SelectBenchmark select(String requestedCourseId, int threads, int capacity) {
        // 安全约束：并发压测会先清空课程的选课记录、压完再把课程删掉，所以**永远只在专用的
        // 隔离课程上跑**，绝不能拿真实课程（例如 CS101）当压测对象 —— 否则会把学生的真实
        // 选课记录一起删掉。请求参数只用于回显，不影响实际压测对象。
        String benchCourseId = "BENCH-SELECT";
        String courseId = requestedCourseId == null || requestedCourseId.isBlank()
                ? "BENCH-SELECT"
                : requestedCourseId.trim();
        int threadCount = Math.max(1, Math.min(threads, MAX_SELECT_THREADS));
        int cap = Math.max(1, capacity);

        try {
            Course course = prepareSelectCourse(benchCourseId, cap);
            List<AuthUser> students = syntheticStudents(threadCount);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        long t0 = System.nanoTime();
        for (int i = 0; i < threadCount; i++) {
            AuthUser me = students.get(i);
            pool.submit(() -> {
                try {
                    startGate.await();
                    try {
                        // 必须打在隔离课程 benchCourseId 上，不能打请求参数里的真实课程，
                        // 否则会把 50 个压测学生写进真实课程的选课记录里。
                        selectWithRetry(me, benchCourseId);
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if ("COURSE_FULL".equals(e.getError()) || "ALREADY_SELECTED".equals(e.getError())) {
                            rejected.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                            addSample(errorSamples, e.getError() + ": " + e.getMessage());
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        addSample(errorSamples, e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        try {
            doneGate.await(180, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        Course finalCourse = courseRepository.findById(benchCourseId).orElseThrow();
        int finalSelected = finalCourse.getSelected() == null ? 0 : finalCourse.getSelected();
        int rows = (int) selectionRepository.countByCourseId(benchCourseId);
        int oversold = Math.max(0, finalSelected - cap);

        log.info("benchmark/select course={} threads={} capacity={} success={} rejected={} oversold={} rows={} errors={}",
                courseId, threadCount, cap, success.get(), rejected.get(), oversold, rows, errors.get());

        return new SelectBenchmark(courseId, cap, threadCount, success.get(), rejected.get(), oversold,
                finalSelected, elapsedMs, errors.get(), rows, List.copyOf(errorSamples));
        } finally {
            // 无论如何都要清理：主库必须回到跑基准之前的样子，不能留下脏课程和脏选课记录
            cleanupSelectCourse(benchCourseId);
        }
    }

    /**
     * 单个线程的选课尝试。
     *
     * <p>H2 在高并发下可能让等待行锁超时抛异常 —— 这属于「基础设施抖动」而不是业务拒绝，
     * 所以对锁超时做少量重试（最多 3 次，重试前完全没有写入，因此不会重复扣名额）；
     * 重试仍失败才如实计入 errors，绝不伪造成 rejected。
     */
    private void selectWithRetry(AuthUser me, String courseId) {
        int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                courseService.selectCourse(me, courseId);
                return;
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                if (attempt == attempts || !isLockTimeout(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(20L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private boolean isLockTimeout(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String text = t.getClass().getName() + " " + String.valueOf(t.getMessage());
            if (text.contains("Lock") || text.contains("lock") || text.contains("Timeout")
                    || text.contains("timeout")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void addSample(ConcurrentLinkedQueue<String> samples, String message) {
        if (samples.size() < 5) {
            samples.add(message == null ? "(无消息)" : message);
        }
    }

    private Course prepareSelectCourse(String courseId, int capacity) {
        Course course = courseRepository.findById(courseId).orElseGet(() -> {
            Course c = new Course();
            c.setCourseId(courseId);
            c.setName("并发选课压测课程");
            c.setCredit(0);
            c.setTeacherId("T1001");
            c.setDescription("仅用于 /api/benchmark/select 的隔离课程，可随时重建。");
            return c;
        });
        selectionRepository.deleteAllByCourseId(courseId);
        course.setCapacity(capacity);
        course.setSelected(0);
        if (course.getCredit() == null) {
            course.setCredit(0);
        }
        if (course.getName() == null) {
            course.setName("并发选课压测课程");
        }
        return courseRepository.save(course);
    }

    /** 为并发压测准备 N 个不同身份的学生（幂等）。 */
    /**
     * 并发压测用的身份：直接在内存里构造 AuthUser，**不往 sys_user 写任何用户**。
     *
     * <p>{@link CourseService#selectCourse} 只用 me.studentId()，而 course_selection.student_id
     * 只是一列普通字符串（没有外键约束），所以压测完全不需要真实用户行 ——
     * 这样压测跑完，用户表、平台统计、教师课程列表都不会多出任何脏数据。
     * 学号用 B1xxx 前缀，与业务数据、与 stats 数据集的 B30xxx 都不会冲突。
     */
    private List<AuthUser> syntheticStudents(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new AuthUser(null, "bench_stu_" + i, "并发测试学生" + i, "student",
                        String.format("B1%03d", i), null, null, null))
                .toList();
    }

    /** 跑完立刻清理压测课程与选课记录，保证主库零污染。 */
    private void cleanupSelectCourse(String courseId) {
        try {
            selectionRepository.deleteAllByCourseId(courseId);
            courseRepository.findById(courseId).ifPresent(courseRepository::delete);
        } catch (Exception e) {
            log.warn("清理压测课程 {} 失败：{}", courseId, e.getMessage());
        }
    }

    // ================================================================== 内部类型

    private record Tally(int submittedOrTotal, int secondValue) {
    }

    private record Measurement(int queryCount, long avgNanos, int rows) {
        long avgMillis() {
            return Math.round(avgNanos / 1_000_000.0);
        }
    }

    /** 返回值：{@code courseId, naive, optimized, speedup, fixture}。 */
    public record StatsBenchmark(String courseId, Block naive, Block optimized, double speedup, Fixture fixture) {
    }

    public record Block(int queryCount, long elapsedMs, int runs, int totalSubmissions, int students, int assignments) {
    }

    public record Fixture(int students, int assignments, double submitRate) {
    }

    /** 返回值：{@code courseId, capacity, threads, success, rejected, oversold, finalSelected, elapsedMs, errors}。 */
    public record SelectBenchmark(
            String courseId,
            int capacity,
            int threads,
            int success,
            int rejected,
            int oversold,
            int finalSelected,
            long elapsedMs,
            int errors,
            int selectionRows,
            List<String> errorSamples) {
    }
}
