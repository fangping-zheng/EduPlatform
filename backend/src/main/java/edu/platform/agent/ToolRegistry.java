package edu.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.platform.security.AuthUser;
import edu.platform.service.AdminService;
import edu.platform.service.AssignmentService;
import edu.platform.service.CourseService;
import edu.platform.service.vo.CourseVO;
import edu.platform.service.vo.KnowledgeVO;
import edu.platform.web.ForbiddenException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具注册中心：14 个**只读**工具，按角色隔离，并在服务端做硬权限校验。
 *
 * <p>设计原则（本项目最重要的边界）：
 * <ul>
 *   <li>所有工具只读 —— 选课、提交作业、评分这类写操作不暴露给模型，模型无法改数据；</li>
 *   <li>工具声明的 role 必须等于当前登录用户的 role，否则 {@code ok=false}，不依赖模型自觉；</li>
 *   <li>handler 只调用已有 service，不在工具里写 SQL；</li>
 *   <li>结果统一序列化为 JSON 字符串，超长按 {@code agent.max-tool-result-chars} 截断。</li>
 * </ul>
 *
 * <p>注意 {@code getCourseKnowledge} 在学生与教师下是两份不同的声明（参数是否必填不同），
 * 因此内部用 {@code role + ':' + name} 作为 key，`get(name)` 只用于展示/调试。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** handler 签名里没有 AuthUser（ToolSpec 的形态是对外契约），因此用线程内的调用者上下文补齐。 */
    private static final ThreadLocal<AuthUser> CURRENT_USER = new ThreadLocal<>();

    private final Map<String, ToolSpec> byKey = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final CourseService courseService;
    private final AssignmentService assignmentService;
    private final AdminService adminService;

    public ToolRegistry(ObjectMapper mapper, AgentProperties props, CourseService courseService,
            AssignmentService assignmentService, AdminService adminService) {
        this.mapper = mapper;
        this.props = props;
        this.courseService = courseService;
        this.assignmentService = assignmentService;
        this.adminService = adminService;
        registerAll();
    }

    // ------------------------------------------------------------------ 对外 API

    public List<ToolSpec> forRole(String role) {
        List<ToolSpec> list = new ArrayList<>();
        for (ToolSpec spec : byKey.values()) {
            if (role == null || spec.role().equals(role)) {
                list.add(spec);
            }
        }
        return list;
    }

    /** 按名字取（同名跨角色时返回第一个），仅用于展示/调试；执行请用 {@link #invoke} 走角色校验。 */
    public ToolSpec get(String name) {
        return byKey.values().stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    public ToolSpec get(String name, String role) {
        return byKey.get(role + ":" + name);
    }

    /** 工具清单（给 GET /api/agent/tools）：只含元信息，不含任何业务数据。 */
    public List<Map<String, Object>> describeForRole(String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolSpec spec : forRole(role)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", spec.name());
            item.put("description", spec.description());
            item.put("role", spec.role());
            item.put("readOnly", spec.readOnly());
            item.put("parameters", spec.parameters());
            out.add(item);
        }
        return out;
    }

    /**
     * 执行工具：不存在 → ok=false；角色不匹配 → ok=false（服务端权限边界）；异常 → ok=false 并记录。
     * 任何情况都不抛异常打断对话。
     */
    public ToolCallLog invoke(String name, Map<String, Object> args, AuthUser me) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        if (me == null) {
            return new ToolCallLog(name, safeArgs, null, 0, false, "未登录");
        }
        ToolSpec spec = get(name, me.role() == null ? "" : me.role());
        if (spec == null) {
            boolean exists = get(name) != null;
            String error = exists
                    ? "权限不足：工具 " + name + " 未对角色 " + me.role() + " 开放"
                    : "工具不存在：" + name;
            log.warn("工具调用被拒绝：{} (role={})", name, me.role());
            return new ToolCallLog(name, safeArgs, null, 0, false, error);
        }

        long t0 = System.nanoTime();
        CURRENT_USER.set(me);
        try {
            Object result = spec.handler().apply(safeArgs);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            String json = mapper.writeValueAsString(result);
            return new ToolCallLog(name, safeArgs, truncate(json), elapsed, true, null);
        } catch (ForbiddenException e) {
            return new ToolCallLog(name, safeArgs, null, (System.nanoTime() - t0) / 1_000_000, false,
                    e.getMessage());
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", name, e.toString());
            return new ToolCallLog(name, safeArgs, null, (System.nanoTime() - t0) / 1_000_000, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            CURRENT_USER.remove();
        }
    }

    private static AuthUser currentUser() {
        AuthUser user = CURRENT_USER.get();
        if (user == null) {
            throw new ForbiddenException("NO_TOOL_CONTEXT", "工具调用缺少用户上下文");
        }
        return user;
    }

    private String truncate(String json) {
        int max = Math.max(200, props.getMaxToolResultChars());
        if (json == null || json.length() <= max) {
            return json;
        }
        return json.substring(0, max) + "...(已截断)";
    }

    // ------------------------------------------------------------------ 注册

    private void registerAll() {
        // ==================== 学生（5 个） ====================
        add(new ToolSpec("getMyCourses", "查询当前学生已选课程与成绩", "student", true, schema(p -> {
        }, List.of()), args -> courseService.listMyCourses(currentUser())));

        add(new ToolSpec("getMyUnsubmittedAssignments", "查询当前学生未提交的作业（含课程名、截止时间、是否已过期）",
                "student", true, schema(p -> {
                }, List.of()),
                args -> assignmentService.listMyUnsubmitted(currentUser())));

        add(new ToolSpec("getMyGrades", "查询当前学生各科成绩与平均分", "student", true, schema(p -> {
        }, List.of()), args -> {
            List<CourseVO> courses = courseService.listMyCourses(currentUser());
            List<Map<String, Object>> rows = new ArrayList<>();
            double sum = 0;
            int graded = 0;
            for (CourseVO c : courses) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("courseId", c.courseId());
                row.put("name", c.name());
                row.put("credit", c.credit());
                row.put("grade", c.grade());
                rows.add(row);
                if (c.grade() != null) {
                    sum += c.grade();
                    graded++;
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("courses", rows);
            out.put("gradedCount", graded);
            out.put("ungradedCount", rows.size() - graded);
            out.put("average", graded == 0 ? null : Math.round(sum / graded * 10) / 10.0);
            return out;
        }));

        add(new ToolSpec("getUpcomingDeadlines", "查询未来 N 天内截止的作业（默认 7 天）", "student", true,
                schema(p -> p.put("days", intProp("天数，默认 7")), List.of()),
                args -> assignmentService.listUpcoming(currentUser(), intArg(args, "days", 7))));

        add(new ToolSpec("getCourseKnowledge", "查询课程知识点大纲；不传 courseId 时取我的第一门课",
                "student", true,
                schema(p -> p.put("courseId", strProp("课程号，例如 CS101，可不传")), List.of()),
                args -> courseService.knowledgeTree(
                        resolveCourseId(strArg(args, "courseId"), currentUser()))));

        // ==================== 教师（6 个） ====================
        add(new ToolSpec("getMyCourses", "查询我名下教授的课程（含选课人数/容量）", "teacher", true, schema(p -> {
        }, List.of()), args -> courseService.listMyCourses(currentUser())));

        add(new ToolSpec("getCourseStudents", "查询某门课的学生名单与成绩", "teacher", true,
                schema(p -> p.put("courseId", strProp("课程号，例如 CS101")), List.of("courseId")),
                args -> {
                    String courseId = requireCourseId(args);
                    courseService.verifyTeacherOwns(courseId, currentUser());
                    return courseService.listStudents(courseId);
                }));

        add(new ToolSpec("getCourseHomeworkStats", "查询班级作业完成度（每人应交/已交/未交，已消除 N+1）",
                "teacher", true,
                schema(p -> p.put("courseId", strProp("课程号，例如 CS101")), List.of("courseId")),
                args -> courseService.getCourseHomeworkStats(currentUser(), requireCourseId(args))));

        add(new ToolSpec("getCourseKnowledge", "查询某门课的知识点大纲", "teacher", true,
                schema(p -> p.put("courseId", strProp("课程号，例如 CS101")), List.of("courseId")),
                args -> {
                    String courseId = requireCourseId(args);
                    courseService.verifyTeacherOwns(courseId, currentUser());
                    return courseService.knowledgeTree(courseId);
                }));

        add(new ToolSpec("getAssignmentSubmissions", "查询某次作业的提交情况（含已交名单与未交名单）",
                "teacher", true,
                schema(p -> p.put("assignId", intProp("作业编号 assignId")), List.of("assignId")),
                args -> assignmentService.submissionOverview(longArg(args, "assignId"), currentUser())));

        add(new ToolSpec("getCourseGradeDistribution", "查询某门课的成绩分段分布、平均分与最高最低分",
                "teacher", true,
                schema(p -> p.put("courseId", strProp("课程号，例如 CS101")), List.of("courseId")),
                args -> courseService.getCourseGradeDistribution(currentUser(), requireCourseId(args))));

        // ==================== 管理员（3 个） ====================
        add(new ToolSpec("getPlatformOverview", "平台总览：用户数（分角色）、课程数、选课总数、提交总数",
                "admin", true, schema(p -> {
                }, List.of()), args -> adminService.overview()));

        add(new ToolSpec("listCourses", "查询全部课程", "admin", true, schema(p -> {
        }, List.of()), args -> adminService.listCourses()));

        add(new ToolSpec("listUsers", "查询用户列表，可按角色过滤", "admin", true,
                schema(p -> p.put("role", strProp("角色过滤：student | teacher | admin，可不传")), List.of()),
                args -> adminService.listUsersByRole(strArg(args, "role"))));
    }

    private void add(ToolSpec spec) {
        byKey.put(spec.role() + ":" + spec.name(), spec);
    }

    // ------------------------------------------------------------------ 参数与 schema 工具

    private interface Props {
        void accept(ObjectNode properties);
    }

    private ObjectNode schema(Props props, List<String> required) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        props.accept(properties);
        root.set("properties", properties);
        ArrayNode requiredNode = mapper.createArrayNode();
        required.forEach(requiredNode::add);
        root.set("required", requiredNode);
        return root;
    }

    private ObjectNode strProp(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "string");
        node.put("description", description);
        return node;
    }

    private ObjectNode intProp(String description) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "integer");
        node.put("description", description);
        return node;
    }

    /** 健壮取值：模型可能给 Integer / Long / String，统一转换。 */
    private static String strArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Long longArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String requireCourseId(Map<String, Object> args) {
        String courseId = strArg(args, "courseId");
        if (courseId == null) {
            throw new edu.platform.web.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "MISSING_COURSE_ID", "缺少课程号参数 courseId");
        }
        return courseId;
    }

    /** 学生工具允许不传课程号：缺省取自己的第一门课。 */
    private String resolveCourseId(String courseId, AuthUser me) {
        if (courseId != null) {
            return courseId;
        }
        List<CourseVO> mine = courseService.listMyCourses(me);
        if (mine.isEmpty()) {
            throw new edu.platform.web.ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "NO_COURSE", "你还没有已选课程");
        }
        return mine.get(0).courseId();
    }

    List<KnowledgeVO> knowledgeOf(String courseId, AuthUser me) {
        courseService.verifyTeacherOwns(courseId, me);
        return courseService.knowledgeTree(courseId);
    }

    public List<ToolSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(byKey.values()));
    }
}
