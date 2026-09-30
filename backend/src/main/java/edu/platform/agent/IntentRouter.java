package edu.platform.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 关键词规则路由（fallback 模式的决策器，也是评测集被测对象）。
 *
 * <p>核心要求：**先按角色过滤候选工具，再匹配关键词**。
 * 否则学生问「提交情况」会命中教师工具，越权拿到别人的数据 —— 这正是评测集里
 * 特意加入的角色混淆用例要证明的事情。
 *
 * <p>命中多个关键词时取「更长关键词」对应的规则（避免「课程」之类的宽泛词误伤）。
 * 路由不到任何工具时返回 {@code null}，由调用方给能力清单兜底。
 */
public class IntentRouter {

    private record Rule(String tool, String role, List<String> keywords) {
    }

    private static final List<Rule> RULES = List.of(
            // ---------------- 学生 ----------------
            new Rule("getMyUnsubmittedAssignments", "student",
                    List.of("没交", "未交", "未提交", "没提交", "作业没", "欠", "还没做")),
            new Rule("getMyGrades", "student",
                    List.of("成绩", "分数", "考了多少", "得分", "考得怎么样")),
            new Rule("getUpcomingDeadlines", "student",
                    List.of("截止", "快到期", "这周要交", "最近.*(作业|交)", "什么时候交", "ddl")),
            new Rule("getCourseKnowledge", "student",
                    List.of("知识点", "大纲", "章节", "学习内容", "知识结构", "要学什么")),
            new Rule("getMyCourses", "student",
                    List.of("我选的课", "选了哪些", "我的课程", "课程列表", "我上了哪些课")),

            // ---------------- 教师 ----------------
            new Rule("getCourseHomeworkStats", "teacher",
                    List.of("完成情况", "完成度", "作业统计", "谁没交", "没交齐", "交齐了吗", "作业情况")),
            new Rule("getCourseStudents", "teacher",
                    List.of("名单", "学生名单", "多少人", "多少学生", "多少名", "有哪些学生", "学生列表")),
            new Rule("getAssignmentSubmissions", "teacher",
                    List.of("提交情况", "交了哪些", "谁的提交", "谁交了", "交了没")),
            new Rule("getCourseGradeDistribution", "teacher",
                    List.of("分布", "分段", "平均分", "最高分", "成绩分析", "分数段")),
            new Rule("getCourseKnowledge", "teacher",
                    List.of("知识点", "大纲", "章节", "学习内容", "知识结构", "要教什么")),
            new Rule("getMyCourses", "teacher",
                    List.of("我选的课", "选了哪些", "我的课程", "课程列表", "我教的课", "我带的课")),

            // ---------------- 管理员 ----------------
            new Rule("getPlatformOverview", "admin",
                    List.of("平台", "总体", "总共.*(用户|课程)", "统计概览", "整体情况", "总量")),
            new Rule("listCourses", "admin",
                    List.of("所有课程", "全部课程", "课程清单", "课程列表", "课程总览")),
            new Rule("listUsers", "admin",
                    List.of("所有用户", "用户列表", "有哪些用户", "用户清单", "账号列表")));

    /**
     * @return 命中的工具名；没有命中返回 {@code null}
     */
    public String route(String question, String role) {
        if (question == null || question.isBlank() || role == null) {
            return null;
        }
        String q = question.trim();
        String bestTool = null;
        int bestLength = -1;
        for (Rule rule : RULES) {
            if (!rule.role().equals(role)) {
                continue;   // 角色过滤：先过滤候选，再匹配关键词
            }
            for (String keyword : rule.keywords()) {
                if (keyword.length() > bestLength && matches(q, keyword)) {
                    bestLength = keyword.length();
                    bestTool = rule.tool();
                }
            }
        }
        return bestTool;
    }

    private boolean matches(String question, String keyword) {
        try {
            return java.util.regex.Pattern.compile(keyword).matcher(question).find();
        } catch (RuntimeException e) {
            return question.contains(keyword);
        }
    }

    /** 供 /api/agent/tools 与调试使用：列出某角色可用的关键词规则。 */
    public List<String> keywordsFor(String role) {
        List<String> all = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.role().equals(role)) {
                all.add(rule.tool() + " ← " + String.join("/", rule.keywords()));
            }
        }
        return all;
    }
}
