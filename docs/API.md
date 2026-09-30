# EduPlatform REST API 契约 v1（冻结版）

> 本文件是前端与后端之间的**唯一契约**。前后端都必须严格按本文件实现，不得自行增删字段或改动路径。
> 所有路径均以 `/api` 为前缀。所有请求/响应体为 JSON（`Content-Type: application/json`）。

## 0. 通用约定

- **认证**：除 `POST /api/auth/login` 外，所有接口都要带请求头
  `Authorization: Bearer <token>`
- **成功响应**：HTTP 200，body 就是数据本身（不额外包 `code/data` 外壳）
- **失败响应**：非 2xx，body 为：
  ```json
  { "error": "COURSE_FULL", "message": "课程已满" }
  ```
- **时间格式**：ISO-8601 字符串，例如 `2026-10-08T23:59:00`
- **角色**：`student` | `teacher` | `admin`

### 演示账号（种子数据）

| 角色 | 用户名 | 密码 |
|---|---|---|
| 管理员 | `admin` | `admin123` |
| 教师 | `t1001` | `123456` |
| 教师 | `t1002` | `123456` |
| 学生 | `s2023001` | `123456` |
| 学生 | `s2023002` | `123456` |
| 学生 | `s2023003` | `123456` |

登录页必须展示这些演示账号（可一键填充），保证面试官免注册即可体验。

---

## 1. 认证

### POST /api/auth/login
请求：`{ "username": "s2023001", "password": "123456" }`

响应：
```json
{
  "token": "eyJhbGciOi...",
  "user": {
    "id": 4,
    "username": "s2023001",
    "realName": "张伟",
    "role": "student",
    "studentId": "2023001",
    "teacherId": null,
    "major": "大数据管理与应用",
    "title": null
  }
}
```

### GET /api/me
响应：同 `user` 对象结构。

---

## 2. 学生端

### GET /api/student/courses
返回**全部可选课程**，字段 `selected` 表示当前学生是否已选。
```json
[ { "courseId":"CS101","name":"数据结构","credit":4,"description":"...",
    "teacherId":"T1001","teacherName":"李明","capacity":60,"selectedCount":37,
    "grade":null,"enrolled":true } ]
```

### GET /api/student/my-courses
返回当前学生**已选课程**，`grade` 为成绩（可为 null）。

### POST /api/student/courses/{courseId}/select
响应：`{ "enrolled": true, "selectedCount": 38 }`
错误（并发抢占满员时）：`409 { "error":"COURSE_FULL", "message":"课程已满" }`

### DELETE /api/student/courses/{courseId}/select
响应：`{ "enrolled": false, "selectedCount": 37 }`

### GET /api/student/assignments
当前学生所有课程的作业，含提交状态。
```json
[ { "assignId":1,"courseId":"CS101","courseName":"数据结构","title":"实验一",
    "description":"...","deadline":"2026-10-08T23:59:00","status":1,
    "publishTime":"2026-09-20T10:00:00","submitted":true,
    "submission":{ "subId":5,"content":"...","submitTime":"...","grade":88.0,"feedback":"不错" } } ]
```

### POST /api/student/assignments/{assignId}/submit
请求：`{ "content": "我的答案" }` → 响应：`{ "subId": 6 }`
错误：`409 ASSIGNMENT_CLOSED`（已撤回或已过截止时间）

### GET /api/student/courses/{courseId}/knowledge
知识点树，`children` 递归。
```json
[ { "nodeId":1,"courseId":"CS101","parentId":null,"nodeName":"第一章 绪论",
    "content":"...","orderNum":1,"children":[ ... ] } ]
```

---

## 3. 教师端

### GET /api/teacher/courses
当前教师的课程列表（`CourseVO` 同学生端结构，`enrolled` 不返回）。

### GET /api/teacher/courses/{courseId}/students
```json
[ { "studentId":"2023001","realName":"张伟","major":"大数据管理与应用","grade":88.0 } ]
```

### GET /api/teacher/courses/{courseId}/assignments
`[ { "assignId":1,"courseId":"CS101","title":"实验一","description":"...","deadline":"...","status":1,"publishTime":"..." } ]`

### POST /api/teacher/courses/{courseId}/assignments
请求：`{ "title":"实验一","description":"...","deadline":"2026-10-08T23:59:00" }` → 响应：`{ "assignId": 7 }`

### DELETE /api/teacher/assignments/{assignId}
撤回作业（status 置 0）→ 响应：`{ "status": 0 }`

### GET /api/teacher/assignments/{assignId}/submissions
```json
[ { "subId":5,"assignId":1,"studentId":"2023001","studentName":"张伟",
    "content":"...","submitTime":"...","grade":88.0,"feedback":"不错" } ]
```

### POST /api/teacher/submissions/{subId}/grade
请求：`{ "grade": 88.0, "feedback": "不错" }` → 响应：`{ "subId":5,"grade":88.0,"feedback":"不错" }`

### GET /api/teacher/courses/{courseId}/stats
**学情完成度统计（已消除 N+1）**。
```json
{ "courseId":"CS101","totalAssignments":3,"totalStudents":5,
  "queryCount": 2,
  "students":[ { "studentId":"2023001","realName":"张伟","totalAssignments":3,
                 "submitted":2,"unsubmitted":1 } ] }
```

### GET /api/teacher/courses/{courseId}/grades
`[ { "studentId":"2023001","realName":"张伟","major":"...","grade":88.0 } ]`

### POST /api/teacher/courses/{courseId}/grades
请求：`{ "studentId":"2023001", "grade": 90.0 }` → 响应：`{ "studentId":"2023001","grade":90.0 }`

---

## 4. 管理员端

### GET /api/admin/users
`[ { "id":1,"username":"admin","realName":"系统管理员","role":"admin","studentId":null,"teacherId":null,"major":null,"title":null } ]`

### POST /api/admin/users
请求：`{ "username":"new1","password":"123456","realName":"新用户","role":"student","studentId":"2023099","teacherId":null,"major":"大数据","title":null }`
响应：`{ "id": 12 }`

### PUT /api/admin/users/{id}
请求：同上（`password` 为空字符串时表示不改密码）。响应：`{ "id": 12 }`

### DELETE /api/admin/users/{id}
响应：`{ "deleted": true }`

### GET /api/admin/courses
同 `CourseVO`，含 `teacherName`。

### POST /api/admin/courses
请求：`{ "courseId":"CS201","name":"操作系统","credit":4,"description":"...","teacherId":"T1001","capacity":50 }`
响应：`{ "courseId":"CS201" }`

### PUT /api/admin/courses/{courseId}
请求：同 POST。响应：`{ "courseId":"CS201" }`

### DELETE /api/admin/courses/{courseId}
响应：`{ "deleted": true }`

---

## 5. 智能体（Agent）

### POST /api/agent/chat
请求：
```json
{ "sessionId": "任意字符串，前端生成即可", "message": "我还有几门作业没交？" }
```

响应：
```json
{
  "reply": "你还有 2 门作业未提交：数据结构-实验三（10-08 截止）、数据库-实验二（10-12 截止）。",
  "toolCalls": [
    { "name":"getMyUnsubmittedAssignments","arguments":{"studentId":"2023001"},
      "result":"...", "elapsedMs": 3, "ok": true }
  ],
  "usage": { "promptTokens": 412, "completionTokens": 96, "llmCalls": 2, "elapsedMs": 1180 },
  "mode": "llm"
}
```
- `mode` 为 `llm`（调用真实大模型）或 `fallback`（无 API Key 时的规则兜底，仍会真实调用业务工具）。
- **无论哪种模式，`toolCalls` 都必须是对真实后端服务的调用记录**，不允许伪造。

### GET /api/agent/tools
返回工具清单，用于面试官查看 Agent 的能力边界。
```json
[ { "name":"getMyUnsubmittedAssignments","description":"查询当前学生未提交的作业",
    "role":"student","readOnly":true,"parameters":{ "type":"object","properties":{}, "required":[] } } ]
```

### GET /api/agent/eval
运行内置评测集（20 条），返回工具调用准确率。
```json
{ "total":20, "passed":19, "accuracy":0.95,
  "cases":[ { "question":"我还有几门作业没交？","expectedTool":"getMyUnsubmittedAssignments",
              "actualTool":"getMyUnsubmittedAssignments","passed":true,"elapsedMs":4 } ] }
```

---

## 6. 基准测试（用于简历量化）

### GET /api/benchmark/stats?courseId=CS101
对比 N+1 实现与优化实现的查询次数与耗时。
```json
{ "courseId":"CS101",
  "naive":     { "queryCount": 46, "elapsedMs": 132 },
  "optimized": { "queryCount": 2,  "elapsedMs": 7 },
  "speedup": 18.9 }
```

### GET /api/benchmark/select?courseId=CS201&threads=50
并发选课压测，验证不超卖。
```json
{ "courseId":"CS201","capacity":50,"threads":50,"success":50,"rejected":0,
  "oversold":0,"finalSelected":50,"elapsedMs":412 }
```
