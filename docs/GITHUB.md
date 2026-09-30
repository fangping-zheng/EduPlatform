# 推到 GitHub：把「我做过」变成「别人能查」

> 为什么单独写这个文件：HR 不会读你的代码，但会看 **仓库长什么样**。
> 一个只有一次 commit、没有 README 的仓库，和一个有清晰提交历史、README 带截图和实测数据的仓库，
> 给人的信任度完全不同 —— 而这是**零成本**可以做到的。

---

## 1. 初始化仓库（我不替你伪造提交历史，下面命令你自己跑）

在 `D:\EduPlatform` 目录下执行。**关键是一次提交只做一件事**，这样历史看起来才像真实开发过程。

```powershell
cd D:\EduPlatform

# 先配好你自己的身份（只对这个仓库生效）
git config user.name "你的名字"
git config user.email "你的邮箱"
git init
git branch -M main

# 提交 1：工程骨架（后端能编译、能启动的最小闭环）
git add backend/pom.xml backend/.mvn backend/mvnw backend/mvnw.cmd backend/.gitignore .gitignore
git commit -m "chore: 初始化 Spring Boot 3.5 + Java 21 工程骨架，接入 Maven Wrapper"

# 提交 2：数据模型与建表脚本
git add backend/src/main/resources/schema.sql backend/src/main/resources/schema-mysql.sql
git add backend/src/main/java/edu/platform/domain backend/src/main/java/edu/platform/repo
git commit -m "feat(domain): 教务领域模型（用户/课程/选课/作业/提交/知识点）与建表脚本"

# 提交 3：认证与权限
git add backend/src/main/java/edu/platform/security backend/src/main/java/edu/platform/config/SecurityConfig.java
git commit -m "feat(auth): JWT 无状态认证 + BCrypt 密码哈希 + 三角色接口级鉴权（401/403）"

# 提交 4：业务逻辑（选课并发、学情统计）
git add backend/src/main/java/edu/platform/service backend/src/main/java/edu/platform/web
git commit -m "feat(course): 选课采用悲观写锁+事务防超卖；学情统计改为单条聚合查询消除 N+1"

# 提交 5：智能体
git add backend/src/main/java/edu/platform/agent
git commit -m "feat(agent): 只读工具注册表 + 角色级工具过滤 + 20 条评测集（准确率 20/20）"

# 提交 6：前端
git add backend/src/main/resources/static
git commit -m "feat(web): 单页前端接入真实 REST API，新增 AI 助手与性能基准面板"

# 提交 7：测试
git add backend/src/test
git commit -m "test: 并发选课超卖、N+1 查询条数、智能体路由、认证越权共 8 个用例"

# 提交 8：工程化与文档
git add backend/Dockerfile backend/.dockerignore docker-compose.yml render.yaml .env.example .github README.md docs
git commit -m "chore: Docker/Compose 部署、GitHub Actions CI、README 与接口文档"

git log --oneline   # 应该看到 8 条提交
```

## 2. 推到 GitHub

```powershell
# 在 GitHub 上新建一个空仓库（不要勾选 README/.gitignore），然后：
git remote add origin https://github.com/你的用户名/edu-platform.git
git push -u origin main
```

推完去仓库页面确认：CI 徽章应该是绿色的（Actions 会自动跑 `backend` 下的测试）。

## 3. 把 README 里的占位符换成真的

- `README.md` 第 6 行的 `REPLACE_ME/edu-platform` 换成你的仓库路径（CI 徽章才会显示）
- `README.md` 里的 `【部署后把链接填这里】` 换成部署好的地址（见 `docs/DEPLOY.md`）
- 建议在 README 顶部放 2～3 张截图或一个 GIF：登录页（能看到演示账号）、教师学情统计、AI 助手展开工具调用记录、性能基准对比

## 4. 检查有没有把敏感信息推上去

推之前一定跑一遍：

```powershell
git status --short          # 应该看不到 .env / data/ / target/
git log --stat | Select-String -Pattern 'env|secret|key'
```

`AGENT_API_KEY`、`JWT_SECRET`、数据库密码**只应该存在于服务器环境变量**里。
一旦推上去，即使后来删掉，GitHub 历史和缓存里仍然找得到 —— 那就得自己去轮换密钥了。

## 5. 关于原来的 surge 链接

原来的 `https://edusystem-demo.surge.sh` 是纯静态假页面（无任何网络请求）。
**建议不要再把它当「在线教育平台」的主演示**。两个处理方式：

1. 直接在简历上换成新地址（推荐）；
2. 如果还想保留，就在 README 里明确写成「v1 静态原型（已废弃，仅存档）」，并在旁边给出 v2 的真实地址 —— 主动交代比被人发现好。
