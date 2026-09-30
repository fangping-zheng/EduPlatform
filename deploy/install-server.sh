#!/usr/bin/env bash
# ============================================================================
# EduPlatform 服务器一键安装脚本（在服务器上以 root 运行）
#
# 设计目标是「最少步骤、最少失败点」：
#   - 不依赖 Docker（国内拉镜像慢、小内存机器构建容易失败）
#   - 只用 JDK 21 + 系统自带 H2 文件库，一条命令起服务
#   - 用 systemd 托管，开机自启、崩溃自动重启
#
# 前置：应用 jar 已上传到 /tmp/edu-platform.jar
# 用法：sudo bash install-server.sh
# ============================================================================
set -euo pipefail

JAR_SRC="${JAR_SRC:-/tmp/edu-platform.jar}"
APP_DIR="/opt/edu-platform"
APP_PORT="${APP_PORT:-8080}"
SERVICE="edu-platform"

log() { printf '\n\033[36m[%s] %s\033[0m\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\n\033[31m[错误] %s\033[0m\n' "$*" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "请用 root 运行：sudo bash $0"
[[ -f "$JAR_SRC" ]]     || die "找不到应用包 $JAR_SRC（请先把 jar 上传到 /tmp）"

# ---------------------------------------------------------------- 1. 装 JDK 21
log "1/5 检查 JDK"
if java -version >/dev/null 2>&1 && java -version 2>&1 | grep -q '"21'; then
  echo "已安装 JDK 21，跳过"
else
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  # 国内机器用官方源足够；若极慢可换成 mirrors.aliyun.com
  apt-get install -y -qq openjdk-21-jre-headless || apt-get install -y -qq openjdk-17-jre-headless \
    || die "JDK 安装失败，请手动安装 openjdk-21-jre-headless"
fi

# ---------------------------------------------------------------- 2. 放应用
log "2/5 部署应用文件到 $APP_DIR"
install -d -m 755 "$APP_DIR"
install -m 640 "$JAR_SRC" "$APP_DIR/app.jar"
# 时间戳命名，方便回滚
cp -f "$APP_DIR/app.jar" "$APP_DIR/app.jar.bak" || true

# ---------------------------------------------------------------- 3. 生成配置
log "3/5 生成环境配置"
ENV_FILE="$APP_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
  echo "已存在 $ENV_FILE，保留原有密钥（不覆盖）"
else
  JWT_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  cat > "$ENV_FILE" <<EOF
# 由 install-server.sh 自动生成，请勿提交到 Git
JWT_SECRET=$JWT_SECRET
SERVER_PORT=$APP_PORT
SPRING_PROFILES_ACTIVE=default
# 大模型 Key：留空则 Agent 跑 fallback 模式（仍会真实调用后端业务工具）
AGENT_API_KEY=
AGENT_BASE_URL=https://api.deepseek.com/v1
AGENT_MODEL=deepseek-chat
EOF
  chmod 600 "$ENV_FILE"
  echo "已生成随机 JWT_SECRET（长度 64）"
fi

# ---------------------------------------------------------------- 4. systemd
log "4/5 配置 systemd 服务（开机自启 + 崩溃自动重启）"
cat > "/etc/systemd/system/${SERVICE}.service" <<EOF
[Unit]
Description=EduPlatform 高校教务与学情分析平台
After=network.target

[Service]
Type=simple
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=/usr/bin/java -Xms128m -Xmx384m -Dfile.encoding=UTF-8 -jar $APP_DIR/app.jar
Restart=always
RestartSec=5
StandardOutput=append:$APP_DIR/app.log
StandardError=append:$APP_DIR/app.log

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "$SERVICE" >/dev/null 2>&1
systemctl restart "$SERVICE"

# ---------------------------------------------------------------- 5. 等健康检查
log "5/5 等待服务启动"
ok=0
for i in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:${APP_PORT}/api/agent/tools" >/dev/null 2>&1; then ok=1; break; fi
  sleep 2
done

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo '你的服务器公网IP')"

if [[ "$ok" -eq 1 ]]; then
  cat <<EOF

============================================================
 ✅ 部署成功

  访问地址： http://${PUBLIC_IP}:${APP_PORT}

  演示账号（登录页也会显示，免注册）：
    管理员  admin    / admin123
    教师    t1001    / 123456
    学生    s2023001 / 123456

  常用运维命令：
    查看状态   systemctl status ${SERVICE}
    查看日志   tail -f ${APP_DIR}/app.log
    重启       systemctl restart ${SERVICE}
    改配置     vi ${APP_DIR}/.env 然后 restart

 ⚠️ 如果浏览器打不开，99% 是云控制台的「安全组/防火墙」
    没放行 ${APP_PORT} 端口 —— 请去控制台加一条入方向规则。
============================================================
EOF
else
  echo
  echo "服务未在 90 秒内就绪，最后 40 行日志："
  tail -n 40 "$APP_DIR/app.log" 2>/dev/null || journalctl -u "$SERVICE" -n 40 --no-pager
  die "启动失败，请把上面的日志发我"
fi
