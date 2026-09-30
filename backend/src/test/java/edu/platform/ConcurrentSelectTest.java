package edu.platform;

import edu.platform.domain.Course;
import edu.platform.domain.User;
import edu.platform.repo.CourseRepository;
import edu.platform.repo.CourseSelectionRepository;
import edu.platform.repo.UserRepository;
import edu.platform.security.AuthUser;
import edu.platform.service.CourseService;
import edu.platform.web.ApiException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发选课压测：50 个线程抢 20 个名额，验证悲观锁（SELECT ... FOR UPDATE）下不会超卖。
 *
 * <p>这是项目的核心卖点之一，因此断言是硬性的：成功数必须正好等于容量、实际选课行数必须等于容量、
 * 意外异常必须为 0、超卖必须为 0。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentSelectTest {

    private static final String COURSE_ID = "TEST-CONC";
    private static final int THREADS = 50;
    private static final int CAPACITY = 20;

    @Autowired
    private CourseService courseService;
    @Autowired
    private CourseRepository courseRepository;
    @Autowired
    private CourseSelectionRepository selectionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void concurrentSelectNeverOversells() throws Exception {
        prepareCourse();
        List<AuthUser> students = prepareStudents(THREADS);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREADS);
        long t0 = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            AuthUser me = students.get(i);
            pool.submit(() -> {
                try {
                    startGate.await();
                    try {
                        selectWithRetry(me);
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if ("COURSE_FULL".equals(e.getError()) || "ALREADY_SELECTED".equals(e.getError())) {
                            rejected.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                            errorSamples.add(e.getError() + ": " + e.getMessage());
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(180, TimeUnit.SECONDS), "并发选课未在 180 秒内完成");
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdownNow();

        Course course = courseRepository.findById(COURSE_ID).orElseThrow();
        int finalSelected = course.getSelected() == null ? 0 : course.getSelected();
        long selectionRows = selectionRepository.countByCourseId(COURSE_ID);
        int oversold = Math.max(0, finalSelected - CAPACITY);

        System.out.println("=== ConcurrentSelectTest ===");
        System.out.println("线程数 threads            = " + THREADS);
        System.out.println("课程容量 capacity         = " + CAPACITY);
        System.out.println("选课成功 success          = " + success.get());
        System.out.println("被拒（满员/重复）rejected  = " + rejected.get());
        System.out.println("意外异常 errors           = " + errors.get());
        System.out.println("课程 selected 最终值      = " + finalSelected);
        System.out.println("course_selection 实际行数  = " + selectionRows);
        System.out.println("超卖 oversold             = " + oversold);
        System.out.println("总耗时 elapsedMs          = " + elapsedMs);
        if (!errorSamples.isEmpty()) {
            System.out.println("异常样例                   = " + errorSamples);
        }

        assertEquals(0, errors.get(), "不应出现意外异常：" + errorSamples);
        assertEquals(CAPACITY, success.get(), "成功选课数必须等于容量");
        assertEquals(THREADS - CAPACITY, rejected.get(), "其余请求应被业务规则（满员）拒绝");
        assertEquals(0, oversold, "不允许超卖");
        assertEquals(CAPACITY, finalSelected, "course.selected 必须等于容量");
        assertEquals(CAPACITY, selectionRows, "course_selection 实际行数必须等于容量");
    }

    /** H2 偶发锁等待超时属于基础设施抖动：重试最多 3 次（重试前没有写入，不会多占名额）。 */
    private void selectWithRetry(AuthUser me) {
        int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                courseService.selectCourse(me, COURSE_ID);
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

    private void prepareCourse() {
        Course course = courseRepository.findById(COURSE_ID).orElseGet(() -> {
            Course c = new Course();
            c.setCourseId(COURSE_ID);
            c.setName("并发选课测试课程");
            c.setCredit(0);
            c.setTeacherId("T1001");
            c.setDescription("ConcurrentSelectTest 专用隔离课程");
            return c;
        });
        selectionRepository.deleteAllByCourseId(COURSE_ID);
        course.setCapacity(CAPACITY);
        course.setSelected(0);
        if (course.getCredit() == null) {
            course.setCredit(0);
        }
        courseRepository.save(course);
    }

    /** 50 个不同学生；BCrypt 只 encode 一次复用，避免 50 次哈希拖慢测试。 */
    private List<AuthUser> prepareStudents(int count) {
        List<String> studentIds = IntStream.rangeClosed(1, count)
                .mapToObj(i -> String.format("TC%04d", i))
                .toList();
        String encoded = passwordEncoder.encode("test123");
        List<User> missing = new ArrayList<>();
        for (int i = 0; i < studentIds.size(); i++) {
            if (userRepository.findByStudentId(studentIds.get(i)).isPresent()) {
                continue;
            }
            User u = new User();
            u.setUsername("tc_" + studentIds.get(i).toLowerCase());
            u.setPassword(encoded);
            u.setRealName(String.format("并发测试学生%02d", i + 1));
            u.setRole("student");
            u.setStudentId(studentIds.get(i));
            u.setMajor("测试数据集");
            u.setCreatedAt(LocalDateTime.now());
            missing.add(u);
        }
        if (!missing.isEmpty()) {
            userRepository.saveAll(missing);
        }
        List<User> all = userRepository.findByStudentIdIn(studentIds);
        List<AuthUser> result = new ArrayList<>();
        for (String studentId : studentIds) {
            all.stream()
                    .filter(u -> Objects.equals(studentId, u.getStudentId()))
                    .findFirst()
                    .ifPresent(u -> result.add(new AuthUser(u.getId(), u.getUsername(), u.getRealName(),
                            u.getRole(), u.getStudentId(), u.getTeacherId(), u.getMajor(), u.getTitle())));
        }
        assertEquals(count, result.size(), "测试学生准备失败");
        return result;
    }
}
