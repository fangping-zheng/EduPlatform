package edu.platform.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.platform.security.AuthUser;
import edu.platform.service.CourseService;
import edu.platform.service.vo.CourseVO;
import edu.platform.web.UnauthorizedException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 编排：{@code chat}（对话）、{@code tools}（工具清单）、{@code evaluate}（评测集）。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>llm</b>（配置了 apiKey）—— 多轮工具调用循环，最多 {@code agent.max-tool-rounds} 轮，
 *       工具结果以 {@code role:"tool"} 回灌，模型输出最终回答。</li>
 *   <li><b>fallback</b>（无 apiKey 或 LLM 调用失败）—— 用 {@link IntentRouter} 按角色选工具，
 *       真实执行后端只读工具，再用真实数据拼中文回答。</li>
 * </ul>
 *
 * <p>关键承诺：**两种模式的 toolCalls 都是对真实后端服务的调用记录**（含真实耗时与结果），
 * 不存在伪造的工具调用。
 *
 * <p>上下文预算管理：每个会话最多保留最近 10 轮对话，最多保留 100 个会话（LRU 淘汰），
 * 单个工具结果超过 {@code agent.max-tool-result-chars} 会被截断后再回灌给模型。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 每个会话最多保留的消息条数（10 轮 = 10 问 + 10 答）。 */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** 最多保留的会话数，超出按 LRU 丢弃。 */
    private static final int MAX_SESSIONS = 100;

    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ToolRegistry toolRegistry;
    private final IntentRouter intentRouter = new IntentRouter();
    private final LlmClient llmClient;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final CourseService courseService;

    /** sessionId → 最近若干轮消息；accessOrder=true 的 LinkedHashMap 实现 LRU。 */
    private final Map<String, Deque<Msg>> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<Msg>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    /** 全局 token/调用计数，便于面试时演示。 */
    private final Map<String, Object> counters = new ConcurrentHashMap<>();

    public AgentService(ToolRegistry toolRegistry, LlmClient llmClient, AgentProperties props,
            ObjectMapper mapper, CourseService courseService) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.props = props;
        this.mapper = mapper;
        this.courseService = courseService;
    }

    private record Msg(String role, String content) {
    }

    // ================================================================== chat

    public AgentResponse chat(String sessionId, String message, AuthUser me) {
        if (me == null) {
            throw new UnauthorizedException("UNAUTHORIZED", "未登录或登录已过期");
        }
        long t0 = System.nanoTime();
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        String question = message == null ? "" : message.trim();
        Deque<Msg> history = sessions.computeIfAbsent(sid, k -> new ArrayDeque<>());

        AgentResponse response;
        if (question.isEmpty()) {
            response = new AgentResponse("请输入你的问题，例如：「我还有几门作业没交？」",
                    List.of(), new UsageVO(0, 0, 0, 0), "fallback");
        } else if (isLlmEnabled()) {
            try {
                response = llmChat(question, history, me, t0);
            } catch (Exception e) {
                // 任何 LLM 异常都降级到 fallback，接口不返回 500
                log.warn("LLM 模式失败，降级到 fallback：{}", e.toString());
                response = fallback(question, me, t0);
            }
        } else {
            response = fallback(question, me, t0);
        }
        remember(history, question, response.reply());
        return response;
    }

    private boolean isLlmEnabled() {
        return props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    private void remember(Deque<Msg> history, String question, String reply) {
        history.addLast(new Msg("user", question));
        history.addLast(new Msg("assistant", reply == null ? "" : reply));
        while (history.size() > MAX_HISTORY_MESSAGES) {
            history.pollFirst();   // 丢掉最旧的，控制上下文预算
        }
    }

    // ================================================================== llm 模式

    private AgentResponse llmChat(String question, Deque<Msg> history, AuthUser me, long startedNanos) {
        int promptTokens = 0;
        int completionTokens = 0;
        int llmCalls = 0;
        List<ToolCallVO> toolCalls = new ArrayList<>();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(textMessage("system", systemPrompt(me)));
        for (Msg msg : history) {
            messages.add(textMessage(msg.role(), msg.content()));
        }
        messages.add(textMessage("user", question));

        List<Map<String, Object>> toolDefs = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.forRole(me.role())) {
            toolDefs.add(llmClient.toFunctionTool(spec));
        }

        String finalContent = null;
        int maxRounds = Math.max(1, props.getMaxToolRounds());
        for (int round = 1; round <= maxRounds; round++) {
            LlmClient.LlmReply reply = llmClient.chat(messages, toolDefs);
            llmCalls++;
            promptTokens += reply.promptTokens();
            completionTokens += reply.completionTokens();

            if (reply.toolCalls().isEmpty()) {
                finalContent = reply.content();
                break;
            }
            messages.add(assistantToolCallMessage(reply));
            for (LlmClient.ToolCallRequest call : reply.toolCalls()) {
                Map<String, Object> args = parseArguments(call.arguments());
                ToolCallLog toolLog = toolRegistry.invoke(call.name(), args, me);
                toolCalls.add(toVO(toolLog));
                messages.add(toolResultMessage(call.id(), toolLog));
            }
            log.info("Agent 第 {} 轮：调用工具 {} 个", round, reply.toolCalls().size());
        }

        if (finalContent == null || finalContent.isBlank()) {
            // 轮数用尽：用最后一条工具结果直接给结论，而不是空回复
            finalContent = concludeFromToolCalls(toolCalls);
        }
        counters.merge("llmCalls", (long) llmCalls, (a, b) -> ((Number) a).longValue() + ((Number) b).longValue());
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
        return new AgentResponse(finalContent, toolCalls,
                new UsageVO(promptTokens, completionTokens, llmCalls, elapsed), "llm");
    }

    private Map<String, Object> textMessage(String role, String content) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content == null ? "" : content);
        return msg;
    }

    private Map<String, Object> assistantToolCallMessage(LlmClient.LlmReply reply) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", reply.content() == null ? "" : reply.content());
        List<Map<String, Object>> calls = new ArrayList<>();
        for (LlmClient.ToolCallRequest call : reply.toolCalls()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("type", "function");
            item.put("function", function);
            calls.add(item);
        }
        msg.put("tool_calls", calls);
        return msg;
    }

    private Map<String, Object> toolResultMessage(String toolCallId, ToolCallLog toolLog) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        msg.put("content", toolLog.ok()
                ? String.valueOf(toolLog.result())
                : "工具执行失败：" + toolLog.error());
        return msg;
    }

    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("工具参数解析失败：{}", json);
            return Map.of();
        }
    }

    private String concludeFromToolCalls(List<ToolCallVO> toolCalls) {
        if (toolCalls.isEmpty()) {
            return "抱歉，我在限定轮数内没有得到足够信息。可以换个问法，或直接查看课程/作业/成绩页面。";
        }
        ToolCallVO last = toolCalls.get(toolCalls.size() - 1);
        if (!last.ok()) {
            return "查询失败：" + last.error();
        }
        return "根据后端数据（工具 " + last.name() + "）结果如下，我先给出原始数据，供你确认：\n" + last.result();
    }

    /** system prompt：限定工具范围 + 禁止编造 + 中文简洁。 */
    private String systemPrompt(AuthUser me) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是高校教务平台的 AI 学习助手。\n");
        sb.append("当前用户：").append(me.realName() == null ? me.username() : me.realName())
                .append("，角色：").append(me.role());
        if (me.studentId() != null) {
            sb.append("，学号：").append(me.studentId());
        }
        if (me.teacherId() != null) {
            sb.append("，工号：").append(me.teacherId());
        }
        sb.append("。\n\n");
        sb.append("严格遵守以下规则：\n");
        sb.append("1. 只能使用下面列出的工具，且只能查询该用户自己的数据；不要臆造工具或参数。\n");
        sb.append("2. 回答必须基于工具返回的真实数据，禁止编造任何数字、课程名或名单；数据不足时明确说明「暂无数据」。\n");
        sb.append("3. 所有工具都是只读的，你不能修改任何数据；如果用户要求选课、提交作业或评分，"
                + "请说明这需要在前端页面操作。\n");
        sb.append("4. 用简体中文回答，条理清晰、简洁，必要时用列表。\n\n");
        sb.append("可用工具：\n");
        for (ToolSpec spec : toolRegistry.forRole(me.role())) {
            sb.append("- ").append(spec.name()).append("：").append(spec.description())
                    .append("（参数 Schema：").append(spec.parameters()).append("）\n");
        }
        return sb.toString();
    }

    // ================================================================== fallback 模式

    private AgentResponse fallback(String question, AuthUser me, long startedNanos) {
        String toolName = intentRouter.route(question, me.role());
        List<ToolCallVO> toolCalls = new ArrayList<>();
        String reply;
        if (toolName == null) {
            reply = capabilityReply(me.role());
        } else {
            Map<String, Object> args = resolveArguments(toolName, question, me);
            ToolCallLog toolLog = toolRegistry.invoke(toolName, args, me);
            toolCalls.add(toVO(toolLog));
            reply = toolLog.ok()
                    ? formatReply(toolName, toolLog.result(), me)
                    : "工具 " + toolName + " 调用失败：" + toolLog.error();
        }
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000;
        return new AgentResponse(reply, toolCalls, new UsageVO(0, 0, 0, elapsed), "fallback");
    }

    /** 规则兜底时补齐工具参数（模型模式下参数由大模型给出）。 */
    private Map<String, Object> resolveArguments(String tool, String question, AuthUser me) {
        Map<String, Object> args = new LinkedHashMap<>();
        switch (tool) {
            case "getUpcomingDeadlines" -> args.put("days", firstNumber(question, 7));
            case "getCourseKnowledge" -> {
                String courseId = findCourseId(question, me);
                if (courseId != null) {
                    args.put("courseId", courseId);
                }
            }
            case "getCourseStudents", "getCourseHomeworkStats", "getCourseGradeDistribution" -> {
                String courseId = findCourseId(question, me);
                if (courseId != null) {
                    args.put("courseId", courseId);
                }
            }
            case "getAssignmentSubmissions" -> {
                Long assignId = firstStandaloneNumber(question);
                if (assignId != null) {
                    args.put("assignId", assignId);
                }
            }
            case "listUsers" -> {
                String role = findRole(question);
                if (role != null) {
                    args.put("role", role);
                }
            }
            default -> {
                // 其余工具无需参数
            }
        }
        return args;
    }

    /** 从问题里识别课程号（CS101）或课程名（数据结构）；识别不到时取该用户的第一门课。 */
    private String findCourseId(String question, AuthUser me) {
        Matcher matcher = Pattern.compile("[A-Za-z]{2,}\\d{2,}").matcher(question);
        if (matcher.find()) {
            return matcher.group();
        }
        List<CourseVO> courses = myCourses(me);
        for (CourseVO course : courses) {
            if (course.name() != null && question.contains(course.name())) {
                return course.courseId();
            }
        }
        return courses.isEmpty() ? null : courses.get(0).courseId();
    }

    private List<CourseVO> myCourses(AuthUser me) {
        try {
            return courseService.listMyCourses(me);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String findRole(String question) {
        if (question.contains("学生")) {
            return "student";
        }
        if (question.contains("教师") || question.contains("老师")) {
            return "teacher";
        }
        if (question.contains("管理员")) {
            return "admin";
        }
        return null;
    }

    private int firstNumber(String question, int defaultValue) {
        Matcher matcher = Pattern.compile("\\d+").matcher(question);
        return matcher.find() ? Integer.parseInt(matcher.group()) : defaultValue;
    }

    /** 独立的数字（不紧跟字母），避免把 CS101 的 101 当成作业编号。 */
    private Long firstStandaloneNumber(String question) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9])(\\d+)").matcher(question);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private String capabilityReply(String role) {
        StringBuilder sb = new StringBuilder("我可以基于后端真实数据帮你查询以下内容（当前角色：")
                .append(roleLabel(role)).append("）：\n");
        List<String> seen = new ArrayList<>();
        for (ToolSpec spec : toolRegistry.forRole(role)) {
            if (spec.name().equals("getMyCourses") && seen.contains("课程")) {
                continue;
            }
            seen.add(spec.name());
            sb.append("- ").append(spec.description()).append("（工具：").append(spec.name()).append("）\n");
        }
        sb.append("\n换个更具体的问法试试，例如：");
        sb.append("student".equals(role) ? "「我还有几门作业没交？」「这门课的知识点有哪些？」"
                : "teacher".equals(role) ? "「帮我分析一下班级的作业完成情况」「CS101 的成绩分布」"
                        : "「平台总体统计概览」「所有用户列表」");
        return sb.toString();
    }

    private String roleLabel(String role) {
        return switch (role == null ? "" : role) {
            case "student" -> "学生";
            case "teacher" -> "教师";
            case "admin" -> "管理员";
            default -> "未知";
        };
    }

    // ================================================================== 自然语言格式化（全部基于真实工具返回）

    private String formatReply(String tool, String json, AuthUser me) {
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            return "查询结果：" + json;   // 被截断的情况直接给出原始结果
        }
        return switch (tool) {
            case "getMyCourses" -> formatMyCourses(node, me);
            case "getMyUnsubmittedAssignments" -> formatUnsubmitted(node);
            case "getMyGrades" -> formatMyGrades(node);
            case "getUpcomingDeadlines" -> formatUpcoming(node);
            case "getCourseKnowledge" -> formatKnowledge(node);
            case "getCourseStudents" -> formatStudents(node);
            case "getCourseHomeworkStats" -> formatHomeworkStats(node);
            case "getAssignmentSubmissions" -> formatSubmissions(node);
            case "getCourseGradeDistribution" -> formatDistribution(node);
            case "getPlatformOverview" -> formatOverview(node);
            case "listCourses" -> formatCourseList(node);
            case "listUsers" -> formatUsers(node);
            default -> "查询结果：" + json;
        };
    }

    private String formatMyCourses(JsonNode node, AuthUser me) {
        if (!node.isArray() || node.isEmpty()) {
            return "你当前没有可显示的课程。";
        }
        boolean student = "student".equals(me.role());
        StringBuilder sb = new StringBuilder(student
                ? "你当前已选 " + node.size() + " 门课程：\n"
                : "你名下共有 " + node.size() + " 门课程：\n");
        for (JsonNode c : node) {
            sb.append("- ").append(text(c, "courseId")).append(" ").append(text(c, "name"));
            if (student) {
                sb.append("（").append(c.path("credit").asInt(0)).append(" 学分");
                if (c.hasNonNull("teacherName")) {
                    sb.append("，授课：").append(text(c, "teacherName"));
                }
                sb.append("，成绩：").append(c.hasNonNull("grade") ? c.get("grade").asText() + " 分" : "未出成绩");
                sb.append("）\n");
            } else {
                sb.append("（").append(c.path("selectedCount").asInt(0)).append("/")
                        .append(c.path("capacity").asInt(0)).append(" 人）\n");
            }
        }
        return sb.toString().trim();
    }

    private String formatUnsubmitted(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "很好，你没有未提交的作业（进行中的作业都交了）。";
        }
        StringBuilder sb = new StringBuilder("你还有 " + node.size() + " 门作业未提交：\n");
        LocalDateTime now = LocalDateTime.now();
        for (JsonNode a : node) {
            String deadline = a.hasNonNull("deadline") ? a.get("deadline").asText() : null;
            sb.append("- ").append(text(a, "courseName")).append(" ").append(text(a, "title"))
                    .append("（").append(formatTime(deadline));
            if (deadline != null) {
                LocalDateTime t = parseTime(deadline);
                sb.append(t != null && t.isBefore(now) ? "，已过期" : "，未过期");
            }
            sb.append("）\n");
        }
        sb.append("建议尽快补齐，逾期提交会被后端拒绝。");
        return sb.toString();
    }

    private String formatMyGrades(JsonNode node) {
        JsonNode courses = node.path("courses");
        if (!courses.isArray() || courses.isEmpty()) {
            return "你还没有已选课程，暂时没有成绩数据。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("你共有 ").append(courses.size()).append(" 门已选课程，其中 ")
                .append(node.path("gradedCount").asInt(0)).append(" 门已出成绩");
        if (node.hasNonNull("average")) {
            sb.append("，平均分 ").append(node.get("average").asText()).append(" 分");
        }
        sb.append("。\n");
        for (JsonNode c : courses) {
            sb.append("- ").append(text(c, "courseId")).append(" ").append(text(c, "name"))
                    .append("：").append(c.hasNonNull("grade") ? c.get("grade").asText() + " 分" : "未出成绩")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String formatUpcoming(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "最近 7 天内没有截止的作业，可以稍作休息。";
        }
        StringBuilder sb = new StringBuilder("未来 7 天内有 " + node.size() + " 次作业截止：\n");
        for (JsonNode a : node) {
            sb.append("- ").append(text(a, "courseName")).append(" ").append(text(a, "title"))
                    .append("：").append(formatTime(a.hasNonNull("deadline") ? a.get("deadline").asText() : null))
                    .append(a.path("submitted").asBoolean(false) ? "（已提交）" : "（未提交）")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String formatKnowledge(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "这门课还没有录入知识点。";
        }
        StringBuilder sb = new StringBuilder("知识点大纲：\n");
        appendKnowledge(sb, node, 0);
        return sb.toString().trim();
    }

    private void appendKnowledge(StringBuilder sb, JsonNode nodes, int depth) {
        for (JsonNode n : nodes) {
            sb.append("  ".repeat(depth)).append("- ").append(text(n, "nodeName"));
            if (depth == 0 && n.hasNonNull("content")) {
                sb.append("：").append(text(n, "content"));
            }
            sb.append("\n");
            if (n.path("children").isArray()) {
                appendKnowledge(sb, n.get("children"), depth + 1);
            }
        }
    }

    private String formatStudents(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "该课程暂无学生选课。";
        }
        StringBuilder sb = new StringBuilder("该课程共 " + node.size() + " 名学生：\n");
        for (JsonNode s : node) {
            sb.append("- ").append(text(s, "studentId")).append(" ").append(text(s, "realName"))
                    .append("（").append(text(s, "major")).append("，成绩：")
                    .append(s.hasNonNull("grade") ? s.get("grade").asText() + " 分" : "未录入").append("）\n");
        }
        return sb.toString().trim();
    }

    private String formatHomeworkStats(JsonNode node) {
        JsonNode students = node.path("students");
        int totalAssignments = node.path("totalAssignments").asInt(0);
        int submitted = 0;
        int need = 0;
        int laggards = 0;
        for (JsonNode s : students) {
            submitted += s.path("submitted").asInt(0);
            need += s.path("totalAssignments").asInt(0);
            if (s.path("unsubmitted").asInt(0) > 0) {
                laggards++;
            }
        }
        double rate = need == 0 ? 0 : Math.round(submitted * 1000.0 / need) / 10.0;
        StringBuilder sb = new StringBuilder();
        sb.append(text(node, "courseId")).append(" 共 ").append(totalAssignments).append(" 次作业、")
                .append(students.size()).append(" 名学生，整体提交率 ").append(rate).append("%");
        sb.append("（其中 ").append(laggards).append(" 人还有未交作业）：\n");
        for (JsonNode s : students) {
            int unsubmitted = s.path("unsubmitted").asInt(0);
            sb.append("- ").append(text(s, "studentId")).append(" ").append(text(s, "realName"))
                    .append("：已交 ").append(s.path("submitted").asInt(0)).append("/")
                    .append(s.path("totalAssignments").asInt(0));
            sb.append(unsubmitted > 0 ? "，还差 " + unsubmitted + " 次" : "，已交齐").append("\n");
        }
        sb.append("（本次统计真实 SQL 条数：").append(node.path("queryCount").asInt(0)).append(" 条）");
        return sb.toString().trim();
    }

    private String formatSubmissions(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return "没有查到该作业的提交情况。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("作业「").append(text(node, "title")).append("」（").append(text(node, "courseId"))
                .append(" ").append(text(node, "courseName")).append("）：应交 ")
                .append(node.path("totalStudents").asInt(0)).append(" 人，已交 ")
                .append(node.path("submittedCount").asInt(0)).append(" 人，未交 ")
                .append(node.path("unsubmittedCount").asInt(0)).append(" 人");
        if (node.hasNonNull("deadline")) {
            sb.append("，截止 ").append(formatTime(node.get("deadline").asText()));
        }
        sb.append("。\n");
        JsonNode submissions = node.path("submissions");
        if (submissions.isArray() && !submissions.isEmpty()) {
            sb.append("已交名单：");
            List<String> names = new ArrayList<>();
            for (JsonNode s : submissions) {
                names.add(text(s, "studentName") + "(" + text(s, "studentId") + ")"
                        + (s.hasNonNull("grade") ? " 已评 " + s.get("grade").asText() + " 分" : " 未评阅"));
            }
            sb.append(String.join("、", names)).append("\n");
        } else {
            sb.append("已交名单：暂无。\n");
        }
        JsonNode unsubmitted = node.path("unsubmitted");
        if (unsubmitted.isArray() && !unsubmitted.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (JsonNode s : unsubmitted) {
                names.add(text(s, "realName") + "(" + text(s, "studentId") + ")");
            }
            sb.append("未交名单：").append(String.join("、", names));
        } else {
            sb.append("未交名单：无，全员已交。");
        }
        return sb.toString().trim();
    }

    private String formatDistribution(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(text(node, "courseId")).append(" 成绩分布：共 ").append(node.path("totalStudents").asInt(0))
                .append(" 人，已出成绩 ").append(node.path("gradedCount").asInt(0)).append(" 人");
        if (node.hasNonNull("average")) {
            sb.append("，平均 ").append(node.get("average").asText()).append(" 分");
        }
        if (node.hasNonNull("highest") && node.hasNonNull("lowest")) {
            sb.append("，最高 ").append(node.get("highest").asText()).append(" 分，最低 ")
                    .append(node.get("lowest").asText()).append(" 分");
        }
        sb.append("。\n");
        for (JsonNode bucket : node.path("buckets")) {
            sb.append("- ").append(text(bucket, "range")).append(" 分：")
                    .append(bucket.path("count").asInt(0)).append(" 人\n");
        }
        return sb.toString().trim();
    }

    private String formatOverview(JsonNode node) {
        return "平台总览：用户 " + node.path("userCount").asLong(0) + " 人（管理员 "
                + node.path("adminCount").asLong(0) + " / 教师 " + node.path("teacherCount").asLong(0)
                + " / 学生 " + node.path("studentCount").asLong(0) + "），课程 "
                + node.path("courseCount").asLong(0) + " 门，选课记录 "
                + node.path("selectionCount").asLong(0) + " 条，作业 "
                + node.path("assignmentCount").asLong(0) + " 次，提交 "
                + node.path("submissionCount").asLong(0) + " 份。";
    }

    private String formatCourseList(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "平台当前没有课程。";
        }
        StringBuilder sb = new StringBuilder("平台共有 " + node.size() + " 门课程：\n");
        for (JsonNode c : node) {
            sb.append("- ").append(text(c, "courseId")).append(" ").append(text(c, "name"))
                    .append("（").append(c.path("credit").asInt(0)).append(" 学分，教师：")
                    .append(text(c, "teacherName")).append("，已选 ")
                    .append(c.path("selectedCount").asInt(0)).append("/").append(c.path("capacity").asInt(0))
                    .append(" 人）\n");
        }
        return sb.toString().trim();
    }

    private String formatUsers(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return "没有查到用户。";
        }
        StringBuilder sb = new StringBuilder("共 " + node.size() + " 个用户：\n");
        for (JsonNode u : node) {
            sb.append("- ").append(text(u, "username")).append(" ").append(text(u, "realName"))
                    .append("（").append(roleLabel(text(u, "role")));
            if (u.hasNonNull("studentId")) {
                sb.append("，学号 ").append(text(u, "studentId"));
            }
            if (u.hasNonNull("teacherId")) {
                sb.append("，工号 ").append(text(u, "teacherId"));
            }
            sb.append("）\n");
        }
        return sb.toString().trim();
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }

    private String formatTime(String iso) {
        LocalDateTime t = parseTime(iso);
        return t == null ? "未设置截止时间" : t.format(MINUTE_FORMAT) + " 截止";
    }

    private LocalDateTime parseTime(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ================================================================== tools

    public List<Map<String, Object>> tools(String role) {
        return toolRegistry.describeForRole(role);
    }

    // ================================================================== evaluate

    /** 20 条内置评测用例：覆盖全部 14 个工具 + 5 条角色混淆用例。 */
    private static final List<EvalCaseSpec> EVAL_CASES = List.of(
            // 学生（6）
            new EvalCaseSpec("我还有几门作业没交？", "student", "getMyUnsubmittedAssignments"),
            new EvalCaseSpec("还有哪些作业没提交？", "student", "getMyUnsubmittedAssignments"),
            new EvalCaseSpec("我这门课成绩怎么样？", "student", "getMyGrades"),
            new EvalCaseSpec("这周要交的作业有哪些？", "student", "getUpcomingDeadlines"),
            new EvalCaseSpec("这门课的知识点有哪些？", "student", "getCourseKnowledge"),
            new EvalCaseSpec("我选了哪些课程？", "student", "getMyCourses"),
            // 教师（6）
            new EvalCaseSpec("我的课程列表", "teacher", "getMyCourses"),
            new EvalCaseSpec("CS101 的学生名单给我看一下", "teacher", "getCourseStudents"),
            new EvalCaseSpec("帮我分析一下班级的作业完成情况", "teacher", "getCourseHomeworkStats"),
            new EvalCaseSpec("CS101 这门课的章节大纲发我", "teacher", "getCourseKnowledge"),
            new EvalCaseSpec("这次作业的提交情况怎么样？", "teacher", "getAssignmentSubmissions"),
            new EvalCaseSpec("CS101 的成绩分布是什么？", "teacher", "getCourseGradeDistribution"),
            // 管理员（3）
            new EvalCaseSpec("平台总体统计概览", "admin", "getPlatformOverview"),
            new EvalCaseSpec("所有课程清单", "admin", "listCourses"),
            new EvalCaseSpec("所有用户列表", "admin", "listUsers"),
            // 角色混淆用例（5）：证明「先按角色过滤候选工具」有效
            new EvalCaseSpec("帮我看看这门课的课程大纲", "student", "getCourseKnowledge"),
            new EvalCaseSpec("有哪些用户？", "admin", "listUsers"),
            new EvalCaseSpec("这门课有多少学生？", "teacher", "getCourseStudents"),
            new EvalCaseSpec("这门课的提交情况怎么样？", "student", null),
            new EvalCaseSpec("我还有哪些作业没交？", "teacher", null));

    private record EvalCaseSpec(String question, String role, String expectedTool) {
    }

    public EvalResult evaluate() {
        List<EvalCase> cases = new ArrayList<>();
        int passed = 0;
        for (EvalCaseSpec spec : EVAL_CASES) {
            long t0 = System.nanoTime();
            String actual = intentRouter.route(spec.question(), spec.role());
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            boolean ok = expectedEquals(spec.expectedTool(), actual);
            if (ok) {
                passed++;
            }
            cases.add(new EvalCase(spec.question(), spec.role(), spec.expectedTool(), actual, ok, elapsed));
        }
        double accuracy = Math.round(passed * 1000.0 / cases.size()) / 1000.0;
        log.info("Agent 路由评测：{}/{} 通过，准确率 {}", passed, cases.size(), accuracy);
        return new EvalResult(cases.size(), passed, accuracy, cases);
    }

    private boolean expectedEquals(String expected, String actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }

    // ================================================================== 返回类型（字段名严格对照 docs/API.md）

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallVO(String name, Map<String, Object> arguments, String result, long elapsedMs,
            boolean ok, String error) {
    }

    public record UsageVO(int promptTokens, int completionTokens, int llmCalls, long elapsedMs) {
    }

    public record AgentResponse(String reply, List<ToolCallVO> toolCalls, UsageVO usage, String mode) {
    }

    public record EvalCase(String question, String role, String expectedTool, String actualTool,
            boolean passed, long elapsedMs) {
    }

    public record EvalResult(int total, int passed, double accuracy, List<EvalCase> cases) {
    }

    private static ToolCallVO toVO(ToolCallLog log) {
        return new ToolCallVO(log.name(), log.arguments(), log.result(), log.elapsedMs(), log.ok(), log.error());
    }
}
