package edu.platform.config;

import edu.platform.domain.Assignment;
import edu.platform.domain.Course;
import edu.platform.domain.CourseSelection;
import edu.platform.domain.KnowledgeNode;
import edu.platform.domain.Submission;
import edu.platform.domain.User;
import edu.platform.repo.AssignmentRepository;
import edu.platform.repo.CourseRepository;
import edu.platform.repo.CourseSelectionRepository;
import edu.platform.repo.KnowledgeNodeRepository;
import edu.platform.repo.SubmissionRepository;
import edu.platform.repo.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动种子数据：仅当 sys_user 为空时执行（幂等，重启不会重复插入）。
 *
 * <p>数据刻意做成「像真实教务系统」：2 名教师、3 名学生、6 门课、部分成绩缺失、
 * CS101 交作业不完整（张伟交 2 欠 1、李娜交 1 欠 2、刘洋全欠，所以「我还有几门作业没交」
 * 有真实答案）、CS101 有一次提交已批改、一次未批改；另有 1 次已撤回作业用于演示 409。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseSelectionRepository selectionRepository;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final KnowledgeNodeRepository knowledgeNodeRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(
            UserRepository userRepository,
            CourseRepository courseRepository,
            CourseSelectionRepository selectionRepository,
            AssignmentRepository assignmentRepository,
            SubmissionRepository submissionRepository,
            KnowledgeNodeRepository knowledgeNodeRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.courseRepository = courseRepository;
        this.selectionRepository = selectionRepository;
        this.assignmentRepository = assignmentRepository;
        this.submissionRepository = submissionRepository;
        this.knowledgeNodeRepository = knowledgeNodeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("检测到已有用户数据，跳过种子数据初始化");
            return;
        }
        log.info("初始化演示种子数据…");

        // ---------------- 用户 ----------------
        user("admin", "admin123", "系统管理员", "admin", null, null, null, null);
        user("t1001", "123456", "李明", "teacher", null, "T1001", null, "副教授");
        user("t1002", "123456", "王芳", "teacher", null, "T1002", null, "讲师");
        User s1 = user("s2023001", "123456", "张伟", "student", "2023001", null, "大数据管理与应用", null);
        User s2 = user("s2023002", "123456", "李娜", "student", "2023002", null, "大数据管理与应用", null);
        User s3 = user("s2023003", "123456", "刘洋", "student", "2023003", null, "大数据管理与应用", null);

        // ---------------- 课程 ----------------
        course("CS101", "数据结构", 4, 60, "T1001",
                "系统学习线性表、栈与队列、树与二叉树、图、查找与排序等经典数据结构，掌握算法复杂度分析方法。");
        course("CS102", "数据库原理", 3, 50, "T1001",
                "关系模型与 SQL、范式与完整性约束、索引与 B+ 树、事务与并发控制、查询优化与执行计划。");
        course("CS103", "Python数据分析", 3, 60, "T1001",
                "使用 NumPy/Pandas 完成数据清洗、聚合与可视化，并结合真实业务数据集做分析报告。");
        course("CS201", "操作系统", 4, 50, "T1002",
                "进程与线程、CPU 调度、同步互斥与死锁、内存管理与分页置换、文件系统与 I/O。");
        course("CS202", "计算机网络", 3, 50, "T1002",
                "分层体系结构，TCP/IP 协议栈、可靠传输、拥塞控制、路由与交换、HTTP 与网络安全基础。");
        course("CS203", "机器学习导论", 3, 40, "T1002",
                "监督与无监督学习、线性模型、决策树与集成学习、模型评估与调优，含课程实战项目。");

        // ---------------- 选课 + 成绩 ----------------
        Map<String, Integer> counts = new HashMap<>();
        grade(select(s1, "CS101", counts), 88.0);
        select(s1, "CS102", counts);
        grade(select(s1, "CS103", counts), 79.5);
        select(s1, "CS201", counts);

        grade(select(s2, "CS101", counts), 92.5);
        grade(select(s2, "CS102", counts), 75.0);
        select(s2, "CS202", counts);
        select(s2, "CS203", counts);

        select(s3, "CS101", counts);
        grade(select(s3, "CS102", counts), 61.0);
        grade(select(s3, "CS103", counts), 84.0);
        grade(select(s3, "CS201", counts), 55.5);
        select(s3, "CS203", counts);

        // course.selected 必须与 course_selection 实际行数一致
        counts.forEach((courseId, n) -> courseRepository.findById(courseId).ifPresent(c -> {
            c.setSelected(n);
            courseRepository.save(c);
        }));

        // ---------------- 作业 ----------------
        LocalDateTime now = LocalDateTime.now();
        Assignment cs101a1 = assignment("CS101", "实验一 线性表的实现",
                "用顺序表和单链表两种方式实现线性表，并对比插入/删除的复杂度。", now.plusDays(7));
        Assignment cs101a2 = assignment("CS101", "实验二 二叉树的遍历",
                "实现二叉树的先序/中序/后序与层序遍历，并完成递归转非递归。", now.plusDays(14));
        Assignment cs101a3 = assignment("CS101", "期中报告 课程设计选题",
                "提交课程设计选题报告：问题背景、数据结构选型、预期结论。", now.plusDays(21));
        assignmentWithStatus("CS101", "实验三 图的最短路径（已撤回）",
                "该作业因教学计划调整已撤回，用于演示提交时的 409 ASSIGNMENT_CLOSED。", now.plusDays(5), 0);

        Assignment cs102a1 = assignment("CS102", "实验一 E-R 模型设计",
                "给定教务系统需求，设计 E-R 图并转换为关系模式。", now.plusDays(10));
        Assignment cs102a2 = assignment("CS102", "实验二 SQL 查询练习",
                "完成 20 道多表连接、分组聚合与子查询练习。", now.plusDays(17));
        Assignment cs201a1 = assignment("CS201", "实验一 进程调度模拟",
                "实现 FCFS/SJF/时间片轮转三种调度算法并输出平均周转时间。", now.plusDays(9));
        Assignment cs201a2 = assignment("CS201", "实验二 页面置换算法",
                "比较 FIFO/LRU/OPT 的缺页率。", now.plusDays(16));

        // CS101：张伟交 2 欠 1，李娜交 1 欠 2，刘洋全欠
        submission(cs101a1, s1, "已完成顺序表与链表两套实现，附复杂度对比表格。", 85.0, "实现完整，边界情况处理到位。");
        submission(cs101a2, s1, "四种遍历均已实现，非递归版本使用显式栈。", null, null);
        submission(cs101a1, s2, "提交顺序表实现与测试用例。", 78.0, "建议补充链表实现与异常场景测试。");
        submission(cs101a3, s2, "选题：校园课程推荐系统的数据结构设计。", null, null);

        // 其他课程少量提交，保证统计有差异
        submission(cs102a1, s1, "E-R 图见附件，共 8 个实体。", 90.0, "结构清晰，可补充弱实体说明。");
        submission(cs102a1, s2, "完成 E-R 图与关系模式转换。", null, null);
        submission(cs201a1, s3, "实现了三种调度算法，附测试数据。", null, null);
        submission(cs102a2, s1, "20 道查询已完成。", null, null);
        submission(cs201a2, s2, "完成缺页率对比分析。", 71.0, "LRU 部分建议补充命中率曲线。");

        // ---------------- 知识点 ----------------
        knowledgeTree("CS101", List.of(
                node("第一章 绪论", "数据结构的研究对象、逻辑结构与存储结构、算法与算法分析。",
                        "1.1 基本概念与术语", "数据、数据元素、逻辑结构与物理结构的基本定义。",
                        "1.2 算法与复杂度", "时间复杂度与空间复杂度的渐进表示（O、Ω、Θ）与分析技巧。"),
                node("第二章 线性表", "线性表的顺序存储与链式存储，以及受限线性结构。",
                        "2.1 顺序表", "顺序表的内存布局、插入删除的平均移动次数、动态扩容。",
                        "2.2 单链表与双向链表", "指针操作、头插尾插、循环链表与双向链表的应用场景。",
                        "2.3 栈与队列", "LIFO/FIFO 特性，括号匹配、表达式求值、循环队列判满判空。"),
                node("第三章 树与二叉树", "树的基本概念、二叉树性质与遍历、哈夫曼编码。",
                        "3.1 二叉树的性质与遍历", "完全二叉树编号性质，先序/中序/后序/层序遍历的递归与非递归实现。",
                        "3.2 哈夫曼树与编码", "带权路径长度最小的构造过程与最优前缀编码。",
                        "3.3 二叉排序树与平衡树", "BST 查找与删除、AVL 旋转、B 树与 B+ 树索引结构。")));

        knowledgeTree("CS102", List.of(
                node("第四章 索引与查询优化", "从 B+ 树索引到执行计划，理解数据库如何高效取数。",
                        "4.1 B+ 树索引", "聚簇索引与二级索引、最左前缀原则、回表与覆盖索引。",
                        "4.2 事务与并发控制", "ACID、隔离级别、MVCC 与锁机制、死锁检测。")));

        knowledgeTree("CS201", List.of(
                node("第五章 进程与线程", "进程/线程模型、调度算法与同步互斥。",
                        "5.1 CPU 调度算法", "FCFS、SJF、优先级、时间片轮转与多级反馈队列的对比。",
                        "5.2 死锁与银行家算法", "死锁四个必要条件、预防与避免、安全序列判定。")));

        log.info("种子数据初始化完成：用户 {} 个，课程 {} 门，作业 {} 个，提交 {} 份",
                userRepository.count(), courseRepository.count(),
                assignmentRepository.count(), submissionRepository.count());
    }

    // ------------------------------------------------------------------ 构造辅助

    private User user(String username, String rawPassword, String realName, String role,
            String studentId, String teacherId, String major, String title) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setRealName(realName);
        u.setRole(role);
        u.setStudentId(studentId);
        u.setTeacherId(teacherId);
        u.setMajor(major);
        u.setTitle(title);
        u.setCreatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    private Course course(String courseId, String name, int credit, int capacity, String teacherId,
            String description) {
        Course c = new Course();
        c.setCourseId(courseId);
        c.setName(name);
        c.setCredit(credit);
        c.setCapacity(capacity);
        c.setSelected(0);
        c.setTeacherId(teacherId);
        c.setDescription(description);
        return courseRepository.save(c);
    }

    private CourseSelection select(User student, String courseId, Map<String, Integer> counts) {
        CourseSelection s = new CourseSelection();
        s.setStudentId(student.getStudentId());
        s.setCourseId(courseId);
        s.setSelectTime(LocalDateTime.now());
        counts.merge(courseId, 1, Integer::sum);
        return selectionRepository.save(s);
    }

    private void grade(CourseSelection selection, Double grade) {
        selection.setGrade(grade);
        selectionRepository.save(selection);
    }

    private Assignment assignment(String courseId, String title, String description, LocalDateTime deadline) {
        return assignmentWithStatus(courseId, title, description, deadline, 1);
    }

    /** status=0 表示已撤回：学生列表里能看到，但提交会被 409 ASSIGNMENT_CLOSED 拒绝。 */
    private Assignment assignmentWithStatus(String courseId, String title, String description,
            LocalDateTime deadline, int status) {
        Assignment a = new Assignment();
        a.setCourseId(courseId);
        a.setTitle(title);
        a.setDescription(description);
        a.setDeadline(deadline);
        a.setStatus(status);
        a.setPublishTime(LocalDateTime.now());
        return assignmentRepository.save(a);
    }

    private void submission(Assignment assignment, User student, String content, Double grade, String feedback) {
        Submission s = new Submission();
        s.setAssignId(assignment.getAssignId());
        s.setStudentId(student.getStudentId());
        s.setContent(content);
        s.setSubmitTime(LocalDateTime.now().minusDays(1));
        s.setGrade(grade);
        s.setFeedback(feedback);
        submissionRepository.save(s);
    }

    /** 父节点 + 若干子节点（childPairs 是「名称, 内容」两两成对）。 */
    private Node node(String name, String content, String... childPairs) {
        List<String[]> children = new ArrayList<>();
        for (int i = 0; i + 1 < childPairs.length; i += 2) {
            children.add(new String[] {childPairs[i], childPairs[i + 1]});
        }
        return new Node(name, content, children);
    }

    private void knowledgeTree(String courseId, List<Node> roots) {
        int order = 1;
        for (Node root : roots) {
            KnowledgeNode parent = saveNode(courseId, null, root.name(), root.content(), order++);
            int childOrder = 1;
            for (String[] child : root.children()) {
                saveNode(courseId, parent.getNodeId(), child[0], child[1], childOrder++);
            }
        }
    }

    private KnowledgeNode saveNode(String courseId, Long parentId, String name, String content, int orderNum) {
        KnowledgeNode n = new KnowledgeNode();
        n.setCourseId(courseId);
        n.setParentId(parentId);
        n.setNodeName(name);
        n.setContent(content);
        n.setOrderNum(orderNum);
        return knowledgeNodeRepository.save(n);
    }

    private record Node(String name, String content, List<String[]> children) {
    }
}
