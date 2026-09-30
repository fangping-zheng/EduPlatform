# Windows 端一键部署：构建 jar → 上传服务器 → 远程安装 → 验证公网可访问
#
# 用法（在 D:\EduPlatform 目录下打开 PowerShell）：
#     powershell -ExecutionPolicy Bypass -File .\deploy\upload-and-deploy.ps1 -Server 123.45.67.89
#
# 可选参数：
#     -Server   服务器公网 IP（必填）
#     -User     SSH 用户，默认 root（阿里云/腾讯云 Ubuntu 默认就是 root）
#     -Port     SSH 端口，默认 22
#     -SshKey   私钥文件路径（用密钥登录时传；不传则用密码，会提示你输入）
#
# 脚本会做四件事：本地打包 → scp 上传 → ssh 执行服务器端安装脚本 → 从公网验证接口

param(
    [Parameter(Mandatory = $true)][string]$Server,
    [string]$User = "root",
    [int]$Port = 22,
    [string]$SshKey = "",
    [int]$AppPort = 8080
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$backend  = Join-Path $repoRoot "backend"
$jar      = Join-Path $backend "target\edu-platform-1.0.0.jar"
$install  = Join-Path $PSScriptRoot "install-server.sh"

$sshArgs = @("-o", "StrictHostKeyChecking=accept-new", "-p", "$Port")
if ($SshKey) { $sshArgs += @("-i", $SshKey) }
$scpArgs = @("-o", "StrictHostKeyChecking=accept-new", "-P", "$Port")
if ($SshKey) { $scpArgs += @("-i", $SshKey) }

function Step($n, $t) { Write-Host "`n=== $n. $t ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 1. 本地打包
Step 1 "本地构建 jar（跳过测试；测试请单独在本地跑）"
if (-not (Test-Path (Join-Path $backend "mvnw.cmd"))) { throw "找不到 backend\mvnw.cmd，请确认在项目根目录运行" }

if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "找不到 Java。请先把 JAVA_HOME 设置成 JDK 21 的目录，或把 java 加入 PATH"
}
Push-Location $backend
try {
    & ".\mvnw.cmd" -B -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "构建失败" }
} finally { Pop-Location }

if (-not (Test-Path $jar)) { throw "没有生成 $jar" }
Write-Host ("  jar 大小: {0:N1} MB" -f ((Get-Item $jar).Length / 1MB))

# ---------------------------------------------------------------- 2. 上传
Step 2 "上传到 $User@${Server}:$Port"
& scp @scpArgs $jar "${User}@${Server}:/tmp/edu-platform.jar"
if ($LASTEXITCODE -ne 0) { throw "jar 上传失败（检查 IP / 端口 / 密码或密钥 / 安全组是否放行 22）" }
& scp @scpArgs $install "${User}@${Server}:/tmp/install-server.sh"
if ($LASTEXITCODE -ne 0) { throw "安装脚本上传失败" }

# ---------------------------------------------------------------- 3. 远程安装
Step 3 "在服务器上执行安装（会安装 JDK、配置 systemd、启动服务）"
& ssh @sshArgs "${User}@${Server}" "chmod +x /tmp/install-server.sh && APP_PORT=$AppPort bash /tmp/install-server.sh"
if ($LASTEXITCODE -ne 0) { throw "远程安装失败，请看上面的输出" }

# ---------------------------------------------------------------- 4. 公网验证
Step 4 "从本地验证公网可访问"
$url = "http://${Server}:${AppPort}"
$ok = $false
foreach ($i in 1..15) {
    try {
        $r = Invoke-WebRequest -Uri "$url/api/agent/tools" -UseBasicParsing -TimeoutSec 8
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { Start-Sleep -Seconds 3 }
}

if ($ok) {
    Write-Host "`n============================================================" -ForegroundColor Green
    Write-Host " ✅ 部署完成，面试官可以直接打开这个链接：" -ForegroundColor Green
    Write-Host "    $url" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "    演示账号：admin/admin123 · t1001/123456 · s2023001/123456"
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "`n把上面的链接填进 README.md 的【部署后把链接填这里】，就可以写进简历了。"
} else {
    Write-Host "`n⚠️ 服务在服务器上起来了，但本地打不开 $url" -ForegroundColor Yellow
    Write-Host "   几乎一定是云控制台的【安全组 / 防火墙】没放行 $AppPort 端口。"
    Write-Host "   去控制台加一条入方向规则：协议 TCP，端口 $AppPort，来源 0.0.0.0/0"
    Write-Host "   加完再访问：$url"
}
