# EduPlatform · 高校教务与学情分析平台（含 LLM 智能体）

> 一个**能真正跑起来、能点开就验证**的教务平台：真实后端、真实数据库、真实权限校验，
> 并且内置一个**只能用受控只读工具读取真实业务数据**的 AI 智能体。

[![CI](https://github.com/REPLACE_ME/edu-platform/actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

**在线演示**：`【部署后把链接填这里】` ｜ **演示账号见下方，免注册即可体验**

---

## 这个项目解决什么问题

原版是一个 Java Swing 桌面程序 + 一个纯静态假页面。桌面程序没法远程演示，静态页面的数据全写死在浏览器里 ——
访客打开 F12 看 Network 面板，**一个请求都没有**，项目可信度直接归零。

这一版把它重做成 Web 系统，重点做了三件事：

1. **让数据是真的**：Spring Boot + 数据库，前端每个操作都是真实 HTTP 请求。
2. **让难点是真的**：不回避并发与性能问题，用可复现的测试给出量化结果。
3. **让 AI 是真的在干活**：智能体不是聊天壳子，它调用的是这个系统自己的业务逻辑。

---

## 快速开始

前置：**JDK 21**（项目自带 Maven Wrapper，不需要单独装 Maven）。

```powershell
git clone <仓库地址> && cd edu-platform/backend
.\mvnw.cmd spring-boot:run
```

打开 <http://localhost:8080> 即可。默认使用 **H2 文件库**，无需安装任何数据库，首次启动会自动建表并写入演示数据。

### 演示账号

| 角色 | 用户名 | 密码 | 能看到什么 |
|---|---|---|---|
| 管理员 | `admin` | `admin123` | 用户管理、课程管理、平台总览 |
| 教师 | `t1001` | `123456` | 课程/学生名单、作业发布与撤回、评分、学情统计、成绩分布 |
| 学生 | `s2023001` | `123456` | 选课/退课、我的课程与成绩、作业提交、知识点大纲 |

### 用 MySQL（生产形态）

```powershell
# 仓库根目录，一条命令起 MySQL + 应用
docker compose up -d --build
```

或手动指定 profile：

```bash
SPRING_PROFILES_ACTIVE=mysql DB_HOST=... DB_USER=... DB_PASSWORD=... java -jar app.jar
```

建表脚本：开发用 `schema.sql`（H2），生产用 `schema-mysql.sql`（MySQL，索引建于表内，因为 MySQL 不支持 `CREATE INDEX IF NOT EXISTS`）。

---

## 架构

```
浏览器（单页应用，原生 JS，无构建步骤）
   │  fetch + Bearer token
   ▼
Spring Security（JWT 无状态认证 · 角色级接口鉴权 401/403）
   ▼
Controller 层   6 个控制器 / 32 个 REST 接口
   ▼
Service 层      业务规则、事务边界、悲观锁、聚合统计
   ├─► AgentService ──► ToolRegistry（12 个只读工具，按角色注册 14 条定义）
   │                        └─► 复用 Service 层，模型无法触达 SQL
   └─► BenchmarkService（N+1 对比 · 并发选课压测，跑在隔离数据集上、跑完即清理）
   ▼
Spring Data JPA ──► H2（开发）/ MySQL（生产）
```

**分层要点**：控制器不写业务规则；事务边界在 Service；数据库写入路径统一在这里加锁与校验。

---

## 三个核心亮点（都有可复现的量化数据）

### 1. 并发选课：50 线程抢 20 个名额，超卖 0 次

选课链路是**数据库悲观写锁**（`SELECT ... FOR UPDATE`）+ 事务 + 回滚，在行锁内完成
「查重 → 容量校验 → 写入选课记录 → 维护冗余计数”。

**为什么用数据库行锁而不是 `synchronized`**：`synchronized` 只能保护单个 JVM，多实例部署立刻失效；共享资源在数据库，锁就必须放在数据库。

实测（`GET /api/benchmark/select?threads=50&capacity=20`）：

```json
{ "threads": 50, "capacity": 20, "success": 20, "rejected": 30,
  "oversold": 0, "finalSelected": 20, "errors": 0, "selectionRows": 20 }
```

**超卖 0 次、选课记录行数与计数完全一致、意外异常 0**。这个压测数据集是隔离的，跑完自动清理，不会污染业务数据。

### 2. N+1 优化：122 条 SQL → 2 条

作业完成度统计的第一版是典型的 N+1：查学生列表（1 条），再对「每个学生 × 每次作业」分别 count（30 × 4 = 120 条）。

优化后用**一条 `GROUP BY` 聚合查询**一次算出结果，加上查作业总数那条，共 2 条。

实测（`GET /api/benchmark/stats`）：

| 实现 | SQL 条数 | 平均耗时（本地 H2，3 次平均） |
|---|---|---|
| 优化前（N+1） | **122** | 64–132 ms |
| 优化后（聚合） | **2** | 2–26 ms |

**怎么测出来的**：自定义了一个 Hibernate `StatementInspector`，拦截每一条真实 SQL 并计数（ThreadLocal 避免并发串号），
接口返回的是**实测条数**而不是估算值。**SQL 条数是确定性指标（122 → 2，减少 120 条）；耗时受本地 H2 缓存与 JIT 影响会在 5～35 倍之间波动，所以对外只把条数当硬指标。**测试里同时断言「两种实现统计出的提交总数一致」，防止优化改变语义。

### 3. AI 智能体：12 个只读工具，路由准确率 20/20

智能体的设计原则是**模型只能通过受控的只读工具读取真实数据**，而不是「把数据库权限交给模型」：

- **不给模型自由写 SQL**：模型只能从预定义工具里选，每个工具是一段参数化、只读的业务逻辑。模型决定「调哪个工具、传什么参数」，不能决定「执行什么 SQL」。
- **权限边界在服务端**：学生身份根本看不到教师工具；即使被提示词注入诱导去调用，也会在 `ToolRegistry` 被拒绝 —— 不依赖模型自觉。
- **不暴露任何写操作**：选课、提交、评分一律不给模型，从架构上排除越权改数据的可能。
- **可评测**：内置 **20 条评测集**（问法 + 角色 + 期望工具），`GET /api/agent/eval` 直接返回准确率与失败用例。评测集里特意包含**角色混淆用例**和**不该命中任何工具的用例**，用来验证角色过滤真的生效。

| 工具 | 角色 | 作用 |
|---|---|---|
| `getMyUnsubmittedAssignments` | student | 我未提交的作业（含截止时间、是否过期） |
| `getMyGrades` / `getMyCourses` | student | 我的成绩 / 已选课程 |
| `getUpcomingDeadlines` | student | 未来 N 天内截止的作业 |
| `getCourseKnowledge` | student / teacher | 课程知识点大纲 |
| `getCourseHomeworkStats` | teacher | 班级作业完成度 |
| `getCourseStudents` | teacher | 课程学生名单 |
| `getAssignmentSubmissions` | teacher | 某次作业的提交情况（含未交名单） |
| `getCourseGradeDistribution` | teacher | 成绩分段分布 |
| `getPlatformOverview` | admin | 平台统计概览 |
| `listCourses` / `listUsers` | admin | 课程 / 用户清单 |

**降级设计**：没有配置 `AGENT_API_KEY` 时走规则路由，**仍然真实调用后端业务接口**，返回的仍是数据库里的真数据，
只是把「理解自然语言」那一层换成关键词匹配。响应里明确标注 `mode: llm` 或 `mode: fallback`，不掩盖。
（Agent 的核心价值是能读到真实数据，模型只是理解层 —— 理解层可降级，数据层不能伪造。）

---

## 配置

全部通过环境变量注入，**不要把密钥写进仓库**：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | 服务端口 |
| `JWT_SECRET` | 开发默认值 | 生产必须换成随机长串 |
| `AGENT_API_KEY` | 空 | 留空则 Agent 走 fallback 模式 |
| `AGENT_BASE_URL` | `https://api.deepseek.com/v1` | 任何 OpenAI 兼容端点 |
| `AGENT_MODEL` | `deepseek-chat` | 模型名 |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | — | 仅 `mysql` profile 使用 |

---

## 测试

```powershell
cd backend
.\mvnw.cmd test
```

| 测试 | 断言什么 |
|---|---|
| `ConcurrentSelectTest` | 50 线程抢 20 个名额：成功 20、超卖 0、异常 0、选课行数与计数一致 |
| `StatsNPlusOneTest` | 优化后 SQL 条数远小于 N+1 实现，且两者统计结果语义一致 |
| `AgentEvalTest` | 20 条评测集工具路由准确率 ≥ 90% |
| `SecurityTest` | 登录返回 token；错误密码 401；无 token 401；学生越权访问 admin 接口 403 |

CI：`.github/workflows/ci.yml`，每次 push 自动编译并跑全部测试。

---

## 目录结构

```
edu-platform/
├─ backend/
│  ├─ src/main/java/edu/platform/
│  │  ├─ domain/        实体（JPA）
│  │  ├─ repo/          仓储
│  │  ├─ service/       业务逻辑（事务 / 悲观锁 / 聚合统计）
│  │  ├─ security/      JWT 签发与解析、当前用户
│  │  ├─ config/        安全配置、种子数据、SQL 计数
│  │  ├─ web/           REST 控制器 + 统一异常处理
│  │  └─ agent/         智能体：工具注册表、路由、LLM 客户端、评测集
│  ├─ src/main/resources/
│  │  ├─ schema.sql / schema-mysql.sql
│  │  ├─ application.yml / application-mysql.yml
│  │  └─ static/index.html     单页前端（无构建步骤）
│  ├─ src/test/         并发 / N+1 / 智能体评测 / 安全测试
│  └─ Dockerfile
├─ docs/
│  ├─ API.md            REST 接口契约（前后端唯一约定）
│  ├─ RESUME.md         简历怎么写 + 面试追问怎么答
│  ├─ DEPLOY.md         部署指南（含安全清单）
│  └─ GITHUB.md         推到 GitHub：结构化提交历史与占位符替换
└─ docker-compose.yml
```

---

## 已知不足（主动说明）

- **LLM 链路未做真实模型压测**：按 OpenAI 兼容协议实现，本地无 Key 时走 fallback；上线换真实模型后会用同一套评测集重新对比。
- **未引入 Redis / 消息队列**：单机 MySQL 的选课并发已用压测验证；QPS 上来后的下一步是「Redis 预扣库存 + 异步落库」，而不是继续加大行锁。
- **前端 token 存在 `sessionStorage`**：对 XSS 有风险。更稳妥的是 `HttpOnly + Secure` Cookie + CSRF Token，这是明确的取舍与改进方向。
- **单机部署**：上线需要加 Nginx 反向代理、HTTPS、数据库主从与监控。
