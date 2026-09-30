# 部署指南

> 目标：让别人点开一个链接就能真实使用你的系统（而不是只有一个静态假页面）。
> 面试官会按 F12 看 Network —— 只要能看到真实的 `/api/...` 请求，可信度就完全不同了。

## 为什么必须换掉 surge.sh

原 Demo 部署在 `surge.sh`，那是**纯静态托管**，只能放 HTML/CSS/JS，跑不了 Java 服务。

这带来两个致命问题：

1. **所有数据都是假的**。前端没有后端可调，只能把数据写死在浏览器里 —— 面试官一看 Network 面板 0 个请求就明白了。
2. **API Key 绝对不能放前端**。静态页面里的 Key 任何人都能扒走，会被人白嫖到账单爆炸，而且面试官一眼就能看出这是不安全的做法。

所以：**前端可以继续静态托管，但后端必须跑在一台真正的服务器上。**

---

## 方案一：本地运行（最快，先确保能跑起来）

不需要装 MySQL，项目默认用 H2 文件库，克隆下来就能跑。

```powershell
# 需要 JDK 21。项目自带 Maven Wrapper，不用单独装 Maven
cd backend
.\mvnw.cmd spring-boot:run
```

然后打开 <http://localhost:8080> ，用登录页上展示的演示账号登录。

想验证后端确实在工作，可以直接看接口：

```powershell
# 登录拿 token
$r = Invoke-RestMethod -Uri http://localhost:8080/api/auth/login -Method Post `
     -ContentType 'application/json' `
     -Body '{"username":"s2023001","password":"123456"}'
$r.token

# 用 token 调业务接口
Invoke-RestMethod -Uri http://localhost:8080/api/student/assignments `
  -Headers @{ Authorization = "Bearer $($r.token)" }
```

---

## 方案二：Docker Compose（本地模拟生产环境：真实 MySQL）

```powershell
# 在仓库根目录
docker compose up -d --build
# 访问 http://localhost:8080
```

这会起两个容器：`mysql:8.0` + 应用（走 `mysql` profile，用真实的 `schema-mysql.sql` 建表）。

可选环境变量（写进同目录的 `.env` 文件即可）：

```env
DB_PASSWORD=换成你自己的密码
JWT_SECRET=换成一串随机长字符串
AGENT_API_KEY=sk-xxxx            # 不填则 Agent 走 fallback 模式
AGENT_BASE_URL=https://api.deepseek.com/v1
AGENT_MODEL=deepseek-chat
```

---

## 方案三：部署到公网服务器（面试官点链接就能用，推荐）

### 3.1 一台便宜云服务器（国内访问快，首选）

阿里云 / 腾讯云的「轻量应用服务器」，2 核 2G 就够，学生机一年几十块。

```bash
# 服务器上（Ubuntu 示例）
sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
git clone <你的仓库地址> edu-platform && cd edu-platform

# 生成安全的密钥，写进 .env
cat > .env <<'EOF'
DB_PASSWORD=用 openssl rand -hex 16 生成的密码
JWT_SECRET=用 openssl rand -hex 32 生成的长随机串
AGENT_API_KEY=
EOF

sudo docker compose up -d --build
```

记得在云服务器安全组放行 80 端口（或你用的端口）。

如果想让链接体面一点（比如 `https://edusystem.你的域名.com`），用 Nginx 反向代理 + Let's Encrypt 免费证书。**不建议把 8080 直接暴露在公网。**

### 3.2 免服务器的托管平台

适合没有服务器、想快速上线的情况：

- **Railway / Render / Fly.io**：都支持 Dockerfile，直接指向 `backend/Dockerfile` 就能构建。
  - 注意：免费额度可能休眠，冷启动要等十几秒；MySQL 通常是收费项，可以用平台自带的 Postgres 或外部免费 MySQL，但要改 `application-mysql.yml` 的驱动和 URL。
- 关键点：**环境变量在平台控制台里配置**，不要提交到仓库。

---

## 部署后的自查清单

上线后逐条确认，这些正是面试官会看的：

- [ ] 打开首页能看到登录页，并且**列出了演示账号**，不用注册就能进
- [ ] F12 → Network：登录后每个操作都有真实的 `/api/...` 请求，状态码 200
- [ ] 用学生账号登录，能看到课程、作业、知识点
- [ ] 用教师账号登录，能发布作业、评分、看学情统计
- [ ] AI 助手面板能问答，并且**每条回复下方能展开看到工具调用记录**（工具名/参数/耗时）
- [ ] 性能基准面板能显示 N+1 优化前后的查询次数对比和并发选课结果
- [ ] 无 token 直接访问 `/api/student/courses` 返回 401（可以用浏览器直接打开该地址验证）
- [ ] `GET /api/agent/tools` 无需登录也能打开（给面试官看能力边界）

## 安全清单（别在这里翻车）

- [ ] `AGENT_API_KEY` 只存在于服务器环境变量里，仓库、前端源码里都没有
- [ ] `JWT_SECRET` 已换成随机长串，不是默认值
- [ ] `DB_PASSWORD` 已改，不是 `root/root`
- [ ] `.gitignore` 已排除 `.env`、`data/`、`target/`
- [ ] 数据库端口 3306 **不要**对公网开放（只给应用容器访问）
- [ ] 生产环境不需要 H2 控制台：`mysql` profile 里已把 `spring.h2.console.enabled` 设为 `false`

---

## 最快的一条路：租一台国内轻量服务器 + 一键脚本

如果你是第一次部署、只想尽快拿到一个能写进简历的链接，走这条。

### 第 1 步：买服务器（约 10 分钟，一年几十块）

推荐**国内轻量应用服务器**，按这个规格买就行：

| 项目 | 选什么 |
|---|---|
| 厂商 | 阿里云 / 腾讯云 的「轻量应用服务器」（学生认证后更便宜） |
| 规格 | **2 核 2G**、带宽 3–5 Mbps、系统盘 40G 以上 |
| 镜像 | **Ubuntu 22.04 或 24.04**（脚本按 Ubuntu 写的） |
| 地域 | 选离你近的（华北/北京），HR 打开更快 |

买完做两件事：
1. **放行端口**：在控制台的「防火墙 / 安全组」里，加一条入方向规则 —— 协议 `TCP`、端口 `8080`、来源 `0.0.0.0/0`。
2. 记下**公网 IP** 和 SSH 密码（或密钥）。

> 关于**备案**：直接用 `http://公网IP:8080` 访问**不需要 ICP 备案**。
> 只有当你把域名指向国内服务器、用 80/443 端口时，才需要备案（7–20 天）。
> 所以先用 IP:端口，今天买今天就能用；想上域名再慢慢备案。

### 第 2 步：一键部署（一条命令）

在你自己的 Windows 电脑上，进入项目根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy\upload-and-deploy.ps1 -Server 你的公网IP
```

脚本会自动完成：本地打包 → scp 上传 → 服务器上装 JDK、配置 systemd 开机自启 → 最后从你本机验证公网可访问，并在结束时打印出**可以直接写进简历的链接**。

如果用的是密钥登录，加参数：`-SshKey C:\path\to\key.pem`。

### 第 3 步：把链接写进简历

脚本跑完会输出类似 `http://123.45.67.89:8080`，把它填进 `README.md` 顶部的占位符，然后：
- 用手机流量打开一次，确认在**外网**也能访问（有些校园网会拦）
- 确认登录页能看到演示账号，免注册即可体验

### 服务器上的运维命令

```bash
systemctl status edu-platform     # 看状态
tail -f /opt/edu-platform/app.log # 看日志
systemctl restart edu-platform    # 重启
vi /opt/edu-platform/.env         # 改配置（JWT_SECRET / AGENT_API_KEY 等）
```

### 为什么不在这台服务器上用 Docker

`docker-compose.yml` 和 `Dockerfile` 都留着了，但一键脚本故意**不用 Docker**：
国内机器拉镜像慢、2G 内存构建镜像容易失败，而本项目自带 H2 文件库，
用「JDK + jar + systemd」这条路步骤最少、失败点最少。
等你有更多内存、要接真实 MySQL 时，再切到 `docker compose up -d --build` 即可。

---

## 零成本方案：Render 免费版（慢，但不要钱）

不想花钱就走这条：

1. 把代码推到 GitHub（没有 git 就先装：`winget install Git.Git`）
2. 打开 <https://render.com> 用 GitHub 账号登录
3. New → **Blueprint** → 选择你的仓库（根目录的 `render.yaml` 会被自动识别）
4. 等构建完成，得到一个 `https://xxxx.onrender.com` 的链接

**必须知道的缺点**：
- 服务器在美国，国内打开可能要 3–10 秒甚至更久
- 免费实例闲置 15 分钟后休眠，**面试官第一次点开要等约 50 秒**
- 所以：可以先用它验证，但真要写简历，还是建议花几十块买国内机器