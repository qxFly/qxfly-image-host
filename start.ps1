#Requires -Version 5.1
<#
.SYNOPSIS
    PicHost 一键启动（Windows / Windows PowerShell 5.1+）。

.DESCRIPTION
    除数据库外，运行所需环境全部取自项目根目录 runtime\，不依赖系统预装的 JDK / Node：
        runtime\win-x64\jdk    便携 JDK 17
        runtime\win-x64\node   便携 Node 20 LTS
    runtime 缺失时会自动调用 scripts\setup-runtime.ps1 下载（加 -Offline 可禁止联网）。

    流程：环境检查 -> 加载 config\local.env -> 数据库探测（MySQL 优先，连不上降级 H2）
    -> 后端增量编译 -> 启动后端并等待健康检查 -> 启动前端 -> 输出访问地址。

.EXAMPLE
    .\start.ps1                     # 开发模式：后端 + Vite 前端
    .\start.ps1 -Mode prod          # 生产模式：单进程，界面由后端 jar 直接提供
    .\start.ps1 -Database h2        # 强制使用 H2 内存库
    .\start.ps1 -Target backend     # 只启动后端
    .\start.ps1 -Target desktop     # 启动 Tauri 桌面端
    .\start.ps1 -Offline            # 禁止联网下载运行时
    .\start.ps1 -Action status      # 查看状态
    .\start.ps1 -Action stop        # 停止
#>
[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string]$Action = 'start',

    [ValidateSet('all', 'backend', 'web', 'desktop')]
    [string]$Target = 'all',

    [ValidateSet('dev', 'prod')]
    [string]$Mode = 'dev',

    [ValidateSet('auto', 'mysql', 'h2')]
    [string]$Database = 'auto',

    [switch]$Rebuild,
    [switch]$NoBrowser,
    [switch]$SkipInstall,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'

$Root        = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Definition }
$ServerDir   = Join-Path $Root 'server'
$WebDir      = Join-Path $Root 'web'
$LogDir      = Join-Path $Root 'logs'
$ConfigDir   = Join-Path $Root 'config'
$RuntimeDir  = Join-Path $Root 'runtime'
$EnvFile     = Join-Path $ConfigDir 'local.env'
$BackendJar  = Join-Path $ServerDir 'target\pichost-server.jar'
$BackendPort = 8080
$WebPort     = 5173
$HealthUrl   = 'http://127.0.0.1:8080/actuator/health'
$WebUrl      = 'http://localhost:5173'
$IsWin       = ($env:OS -eq 'Windows_NT')

$script:StartedPids = @()
$script:EffectiveDb = 'mysql'

# ------------------------------------------------------------------ 输出
function Write-Banner {
    Write-Host ''
    Write-Host '  ============================================================' -ForegroundColor DarkCyan
    Write-Host '     PicHost 多云端图床管理  ·  一键启动' -ForegroundColor White
    Write-Host '  ============================================================' -ForegroundColor DarkCyan
}
function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

# java / mvn / npm / netstat 等原生程序会把正常输出写进 stderr。
# 在 ErrorActionPreference='Stop' 下 PowerShell 5.1 会把它当终止性错误中断脚本，
# 因此所有原生调用都要临时把 ErrorActionPreference 降级为 Continue。
function Invoke-Native {
    param([Parameter(Mandatory = $true)][scriptblock]$Script)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return & $Script } finally { $ErrorActionPreference = $previous }
}

# actuator 的 content-type 是 vnd.spring-boot.actuator+json，
# Invoke-WebRequest 不当作文本，Content 是 byte[]，直接正则匹配永远匹配不上。
function Get-WebText {
    param([string]$Url, [int]$TimeoutSec = 5)
    $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    $content = $resp.Content
    if ($content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($content) }
    return [string]$content
}

# ---------------------------------------------------------------- 便携运行时
function Resolve-Runtime {
    $rt = @{ Java = $null; JavaHome = $null; NodeDir = $null; NpmCmd = $null }
    $jdkHome = Join-Path $RuntimeDir 'win-x64\jdk'
    if (Test-Path (Join-Path $jdkHome 'bin\java.exe')) {
        $rt.JavaHome = $jdkHome
        $rt.Java = Join-Path $jdkHome 'bin\java.exe'
    }
    $nodeDir = Join-Path $RuntimeDir 'win-x64\node'
    if (Test-Path (Join-Path $nodeDir 'node.exe')) {
        $rt.NodeDir = $nodeDir
        $rt.NpmCmd = Join-Path $nodeDir 'npm.cmd'
    }
    return $rt
}

function Enable-Runtime {
    param($Runtime)
    if ($Runtime.JavaHome) {
        $env:JAVA_HOME = $Runtime.JavaHome
        $env:Path = (Join-Path $Runtime.JavaHome 'bin') + ';' + $env:Path
    }
    if ($Runtime.NodeDir) { $env:Path = $Runtime.NodeDir + ';' + $env:Path }
}

function Install-RuntimeIfNeeded {
    param($Runtime, [bool]$NeedNode)
    $missing = (-not $Runtime.JavaHome) -or ($NeedNode -and -not $Runtime.NodeDir)
    if (-not $missing) { return $Runtime }
    if ($Offline) { Write-Warn 'runtime\ 缺少便携运行时，且已指定 -Offline'; return $Runtime }
    $setup = Join-Path $Root 'scripts\setup-runtime.ps1'
    if (Test-Path $setup) {
        Write-Step '首次运行：下载便携运行时到 runtime\（约 300MB，仅一次）...'
        & powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $setup -Platform win
        return (Resolve-Runtime)
    }
    return $Runtime
}

# 读取 config\local.env（由 mysql-service.cmd init 生成）并注入进程环境变量
function Import-LocalEnv {
    if (-not (Test-Path $EnvFile)) { return $false }
    foreach ($line in (Get-Content $EnvFile -Encoding UTF8)) {
        if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process')
        }
    }
    return $true
}

# ---------------------------------------------------------------- 网络
function Test-Port {
    param([int]$Port, [string]$HostName = '127.0.0.1', [int]$TimeoutMs = 1200)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port -Port $Port) { return $true }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

function Wait-Health {
    param([string]$Url, [int]$TimeoutSeconds = 180, $Process = $null)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $tick = 0
    while ((Get-Date) -lt $deadline) {
        if ($Process -and $Process.HasExited) { Write-Host ''; return $false }
        try {
            if ((Get-WebText -Url $Url) -match '"status"\s*:\s*"UP"') { Write-Host ''; return $true }
        } catch {
            # 尚未就绪
        }
        if (($tick % 6) -eq 0) { Write-Host -NoNewline '.' -ForegroundColor DarkGray }
        $tick = $tick + 1
        Start-Sleep -Milliseconds 900
    }
    Write-Host ''
    return $false
}

function Wait-Http {
    param([string]$Url, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 | Out-Null
            return $true
        } catch {
            if ($_.Exception.Response) { return $true }
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

# ---------------------------------------------------------------- 进程
function Get-PortOwner {
    param([int]$Port)
    $found = @()
    try {
        $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop
        foreach ($conn in $conns) {
            $ownerId = [int]$conn.OwningProcess
            if ($ownerId -gt 0) { $found += $ownerId }
        }
    } catch {
        $lines = Invoke-Native { netstat.exe -ano 2>$null } | Select-String -Pattern 'LISTENING' | Select-String -Pattern (':' + $Port + '\s')
        foreach ($line in $lines) {
            $parts = ($line.ToString().Trim() -split '\s+')
            if ($parts.Length -ge 5) {
                $ownerId = 0
                if ([int]::TryParse($parts[$parts.Length - 1], [ref]$ownerId) -and $ownerId -gt 0) { $found += $ownerId }
            }
        }
    }
    return ($found | Sort-Object -Unique)
}

# Vite 默认只监听 ::1，用 127.0.0.1 探测会把"运行中"误判为"未运行"
function Test-Listening {
    param([int]$Port)
    return (@(Get-PortOwner -Port $Port).Count -gt 0)
}

function Stop-ProcessTree {
    param([int]$ProcessId, [string]$Name = 'process')
    if ($ProcessId -le 0) { return }
    $proc = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $proc) { return }
    Write-Step ('停止 ' + $Name + ' (PID ' + $ProcessId + ', ' + $proc.ProcessName + ')')
    if ($IsWin) { Invoke-Native { taskkill.exe /T /F /PID $ProcessId 2>$null } | Out-Null }
    else { Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue }
}

function Stop-ServiceOnPort {
    param([int]$Port, [string]$Name)
    $owners = Get-PortOwner -Port $Port
    if (-not $owners -or $owners.Count -eq 0) {
        Write-Info ($Name + ' 未在运行（端口 ' + $Port + ' 空闲）')
        return
    }
    foreach ($ownerId in $owners) { Stop-ProcessTree -ProcessId $ownerId -Name $Name }
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline -and (Test-Listening -Port $Port)) { Start-Sleep -Milliseconds 500 }
}

function Stop-StartedProcesses {
    foreach ($startedId in $script:StartedPids) { Stop-ProcessTree -ProcessId $startedId -Name '本脚本启动的进程' }
    $script:StartedPids = @()
    Stop-ServiceOnPort -Port $BackendPort -Name '后端服务'
    Stop-ServiceOnPort -Port $WebPort -Name '前端服务'
}

# ---------------------------------------------------------------- 环境检查
function Assert-Command {
    param([string]$Name, [string]$Hint)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw ('未找到 ' + $Name + '。' + $Hint) }
}

function Test-Prerequisites {
    param([string]$Which, $Runtime)
    if ($Which -eq 'backend' -or $Which -eq 'all') {
        if (-not $Runtime.Java) {
            Assert-Command -Name 'java' -Hint '请安装 JDK 17+，或先运行 scripts\setup-runtime.ps1 下载便携运行时'
        }
        $javaLine = Invoke-Native { & $script:JavaExe -version 2>&1 } | Select-Object -First 1
        Write-Info ('Java   : ' + $javaLine)
    }
    if ($Which -eq 'web' -or $Which -eq 'desktop' -or $Which -eq 'all') {
        if (-not $Runtime.NodeDir) {
            Assert-Command -Name 'npm' -Hint '请安装 Node.js 18+，或先运行 scripts\setup-runtime.ps1 下载便携运行时'
        }
        $nodeLine = Invoke-Native { & $script:NodeExe -v 2>&1 } | Select-Object -First 1
        Write-Info ('Node   : ' + $nodeLine)
    }
}

# ---------------------------------------------------------------- 数据库
function Resolve-DatabaseMode {
    if ($Database -ne 'auto') {
        Write-Info ('按参数指定使用 ' + $Database + ' 模式')
        return $Database
    }

    Write-Step '探测本地 MySQL ...'
    if (Test-Port -Port 3306) {
        Write-Ok '检测到 MySQL（127.0.0.1:3306），使用 MySQL 模式'
        return 'mysql'
    }
    Write-Info '127.0.0.1:3306 上没有可用的 MySQL'

    if (Get-Command docker -ErrorAction SilentlyContinue) {
        Write-Step '尝试用 docker compose 启动 MySQL 容器 ...'
        Push-Location $Root
        try { Invoke-Native { docker compose up -d mysql 2>&1 } | Out-Null }
        catch { Write-Warn ('docker compose 启动失败：' + $_.Exception.Message) }
        finally { Pop-Location }
        if (Wait-Port -Port 3306 -TimeoutSeconds 90) {
            Write-Ok 'MySQL 容器已就绪'
            return 'mysql'
        }
        Write-Warn 'MySQL 容器未在 90 秒内就绪，继续降级'
    } else {
        Write-Info '未安装 Docker，跳过容器方式'
    }

    Write-Warn '自动降级为 H2 内存库：功能可完整体验，但重启后数据清空'
    Write-Info '要持久化：.\mysql-service.cmd init -User root -Password 你的密码'
    return 'h2'
}

# ---------------------------------------------------------------- 后端
function Test-JarHasWeb {
    param([string]$Jar)
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
        try {
            foreach ($entry in $zip.Entries) {
                if ($entry.FullName -eq 'BOOT-INF/classes/static/index.html') { return $true }
            }
            return $false
        } finally { $zip.Dispose() }
    } catch {
        return $false
    }
}

function Test-NeedRebuild {
    if ($Rebuild) { return $true }
    if (-not (Test-Path $BackendJar)) { return $true }
    if ($Mode -eq 'prod' -and -not (Test-JarHasWeb -Jar $BackendJar)) { return $true }
    $jarTime = (Get-Item $BackendJar).LastWriteTime
    $sources = @()
    $srcDir = Join-Path $ServerDir 'src'
    if (Test-Path $srcDir) { $sources += Get-ChildItem -Path $srcDir -Recurse -File }
    $pom = Join-Path $ServerDir 'pom.xml'
    if (Test-Path $pom) { $sources += Get-Item $pom }
    $distDir = Join-Path $WebDir 'dist'
    if ($Mode -eq 'prod' -and (Test-Path $distDir)) { $sources += Get-ChildItem -Path $distDir -Recurse -File }
    foreach ($file in $sources) {
        if ($file.LastWriteTime -gt $jarTime) { return $true }
    }
    return $false
}

function Invoke-BackendBuild {
    $mvn = Join-Path $ServerDir 'mvnw.cmd'
    if (-not (Test-Path $mvn)) { $mvn = Join-Path $ServerDir 'mvnw' }
    if (-not (Test-Path $mvn)) { throw ('找不到 Maven 包装器：' + $mvn) }

    $buildArgs = @('-B', '-DskipTests')
    if ($Mode -eq 'prod') {
        Write-Step '生产模式：构建前端并打进 jar ...'
        Push-Location $WebDir
        try {
            if (-not $SkipInstall) { Invoke-Native { & $script:NpmCmd install --no-audit --no-fund } }
            Invoke-Native { & $script:NpmCmd run build }
            if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
        } finally { Pop-Location }
        $buildArgs += '-Pwith-web'
    }

    Write-Step '编译后端（首次运行需要下载 Maven 与全部依赖，可能耗时数分钟）...'
    Push-Location $ServerDir
    try {
        Invoke-Native { & $mvn @buildArgs package }
        if ($LASTEXITCODE -ne 0) { throw ('Maven 构建失败，退出码 ' + $LASTEXITCODE) }
    } finally { Pop-Location }
    Write-Ok '后端编译完成'
}

function Start-Backend {
    param([string]$DbMode, [switch]$Detached)

    if (Test-Listening -Port $BackendPort) {
        Write-Warn ('端口 ' + $BackendPort + ' 已被占用，复用已在运行的后端')
        return $true
    }

    if (Test-NeedRebuild) { Invoke-BackendBuild } else { Write-Ok '后端产物已是最新（源码无变更，跳过编译）' }

    $javaArgs = @('-Dfile.encoding=UTF-8', '-jar', $BackendJar)
    # Start-Process 的 -ArgumentList 不会自动加引号，jar 路径含空格时必须自己包好
    $javaArgLine = '-Dfile.encoding=UTF-8 -jar "' + $BackendJar + '"'
    if ($DbMode -eq 'h2') {
        $javaArgs += '--spring.profiles.active=h2'
        $javaArgLine += ' --spring.profiles.active=h2'
    }

    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

    Write-Step ('启动后端（' + $DbMode + ' 模式，端口 ' + $BackendPort + '）...')
    if (-not $Detached) {
        & $script:JavaExe $javaArgs
        return $true
    }

    $stdout = Join-Path $LogDir 'backend.log'
    $stderr = Join-Path $LogDir 'backend.err.log'
    $proc = Start-Process -FilePath $script:JavaExe -ArgumentList $javaArgLine -WorkingDirectory $ServerDir -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $script:StartedPids += $proc.Id
    Write-Info ('日志 : ' + $stdout)

    if (Wait-Health -Url $HealthUrl -TimeoutSeconds 180 -Process $proc) {
        Write-Ok '后端健康检查通过'
        return $true
    }

    Write-Fail '后端启动失败'
    foreach ($logName in @('backend.log', 'backend.err.log')) {
        $logFile = Join-Path $LogDir $logName
        if ((Test-Path $logFile) -and ((Get-Item $logFile).Length -gt 0)) {
            Write-Info ($logName + ' 最后几行：')
            Get-Content -Path $logFile -Tail 12 -Encoding UTF8 -ErrorAction SilentlyContinue | ForEach-Object {
                Write-Host ('        ' + $_) -ForegroundColor DarkGray
            }
        }
    }
    return $false
}

function Start-BackendWithFallback {
    param([string]$DbMode, [switch]$Detached)

    if (Start-Backend -DbMode $DbMode -Detached:$Detached) {
        $script:EffectiveDb = $DbMode
        return
    }

    if ($DbMode -eq 'mysql' -and $Database -eq 'auto') {
        Write-Warn '无法连接 MySQL（账号密码不对，或该账号无权建库）'
        Write-Info '要固定用 MySQL：先执行 .\mysql-service.cmd init -User root -Password 你的密码'
        Write-Warn '本次自动改用 H2 内存库（数据不持久）...'
        Stop-ServiceOnPort -Port $BackendPort -Name '后端服务'
        if (Start-Backend -DbMode 'h2' -Detached:$Detached) {
            $script:EffectiveDb = 'h2'
            return
        }
    }
    throw '后端启动失败，请检查 logs\backend.log'
}

# ---------------------------------------------------------------- 前端
function Install-WebDependencies {
    if ($SkipInstall) { return }
    if (Test-Path (Join-Path $WebDir 'node_modules')) { return }
    Write-Step '安装前端依赖（首次运行需要几分钟）...'
    Push-Location $WebDir
    try {
        Invoke-Native { & $script:NpmCmd install --no-audit --no-fund }
        if ($LASTEXITCODE -ne 0) { throw 'npm install 失败' }
    } finally { Pop-Location }
    Write-Ok '前端依赖安装完成'
}

function Start-Frontend {
    param([string]$Which, [switch]$Detached)

    Install-WebDependencies

    if ($Which -eq 'desktop') {
        Write-Step '启动 Tauri 桌面端（首次会编译 Rust 依赖，耗时较长）...'
        Push-Location $WebDir
        try { Invoke-Native { & $script:NpmCmd run tauri:dev } } finally { Pop-Location }
        return
    }

    if (Test-Listening -Port $WebPort) {
        Write-Warn ('端口 ' + $WebPort + ' 已被占用，复用已在运行的前端')
        return
    }

    if (-not (Test-Listening -Port $BackendPort)) { Write-Warn '后端未在运行：页面能打开，但登录与上传会失败' }
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }

    Write-Step ('启动前端开发服务器（端口 ' + $WebPort + '）...')
    if (-not $Detached) {
        Push-Location $WebDir
        try { Invoke-Native { & $script:NpmCmd run dev } } finally { Pop-Location }
        return
    }

    $stdout = Join-Path $LogDir 'web.log'
    $stderr = Join-Path $LogDir 'web.err.log'
    $proc = Start-Process -FilePath $script:NpmCmd -ArgumentList @('run', 'dev') -WorkingDirectory $WebDir -NoNewWindow -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $script:StartedPids += $proc.Id
    Write-Info ('日志 : ' + $stdout)

    if (Wait-Http -Url $WebUrl -TimeoutSeconds 120) { Write-Ok '前端已就绪' }
    else { Write-Fail '前端未在 120 秒内就绪，请查看 logs\web.log'; throw '前端启动失败' }
}

# ---------------------------------------------------------------- 状态
function Show-Status {
    Write-Host ''
    $backendOwners = @(Get-PortOwner -Port $BackendPort)
    $webOwners = @(Get-PortOwner -Port $WebPort)

    if ($backendOwners.Count -gt 0) {
        $health = 'DOWN'
        try { if ((Get-WebText -Url $HealthUrl) -match '"status"\s*:\s*"UP"') { $health = 'UP' } } catch { $health = 'DOWN' }
        Write-Host ('  后端  : 运行中   端口 ' + $BackendPort + '   PID ' + ($backendOwners -join ',') + '   健康 ' + $health) -ForegroundColor Green
    } else {
        Write-Host '  后端  : 未运行' -ForegroundColor DarkGray
    }

    if ($webOwners.Count -gt 0) {
        Write-Host ('  前端  : 运行中   端口 ' + $WebPort + '   PID ' + ($webOwners -join ',') + '   ' + $WebUrl) -ForegroundColor Green
    } else {
        Write-Host '  前端  : 未运行' -ForegroundColor DarkGray
    }

    if (Test-Port -Port 3306) { Write-Host '  MySQL : 可连接   127.0.0.1:3306' -ForegroundColor Green }
    else { Write-Host '  MySQL : 不可连接（后端可能正运行在 H2 模式）' -ForegroundColor DarkGray }

    $rt = Resolve-Runtime
    if ($rt.JavaHome) { Write-Host '  运行时 : 使用项目自带 runtime\win-x64（离线可用）' -ForegroundColor Green }
    else { Write-Host '  运行时 : 使用系统 PATH 中的 JDK / Node' -ForegroundColor DarkGray }

    if (Test-Path $EnvFile) { Write-Host '  数据库 : 已读取 config\local.env' -ForegroundColor Green }
    else { Write-Host '  数据库 : 无 config\local.env（用默认口令或 H2）' -ForegroundColor DarkGray }
    Write-Host ''
}

function Show-Summary {
    param([string]$Which, [string]$DbMode)
    Write-Host ''
    Write-Host '  ------------------------------------------------------------' -ForegroundColor DarkCyan
    if ($Which -eq 'all' -or $Which -eq 'backend') {
        Write-Host ('   后端接口 : http://127.0.0.1:' + $BackendPort + '    [数据库 ' + $DbMode + ']') -ForegroundColor White
        Write-Host ('   健康检查 : http://127.0.0.1:' + $BackendPort + '/actuator/health') -ForegroundColor Gray
    }
    if ($Mode -eq 'prod') {
        Write-Host ('   管理界面 : http://127.0.0.1:' + $BackendPort + '   （单进程，界面已打进 jar）') -ForegroundColor White
    } elseif ($Which -eq 'all' -or $Which -eq 'web') {
        Write-Host ('   管理界面 : ' + $WebUrl) -ForegroundColor White
    }
    if ($Which -ne 'desktop') {
        Write-Host '   默认账号 : admin / admin123   （登录后请立即修改密码）' -ForegroundColor Yellow
    }
    Write-Host '  ------------------------------------------------------------' -ForegroundColor DarkCyan
    Write-Host '   停止服务 : Ctrl+C   或   .\start.ps1 -Action stop' -ForegroundColor DarkGray
    Write-Host '   查看日志 : logs\backend.log   logs\web.log' -ForegroundColor DarkGray
    Write-Host ''
}

# ================================================================== 主流程
Write-Banner

if (-not (Test-Path (Join-Path $ServerDir 'pom.xml')) -or -not (Test-Path (Join-Path $WebDir 'package.json'))) {
    Write-Fail ('当前目录不像是 PicHost 项目根目录：' + $Root)
    Write-Info '请把本脚本放在与 server、web 同级的位置后重试'
    exit 1
}

if ($Mode -eq 'prod' -and $Target -eq 'all') { $Target = 'backend' }

$Runtime = Resolve-Runtime
if ($Action -eq 'start' -or $Action -eq 'restart') {
    $needNode = ($Mode -eq 'dev' -and ($Target -eq 'all' -or $Target -eq 'web' -or $Target -eq 'desktop'))
    $Runtime = Install-RuntimeIfNeeded -Runtime $Runtime -NeedNode $needNode
}
Enable-Runtime -Runtime $Runtime
$script:JavaExe = if ($Runtime.Java) { $Runtime.Java } else { 'java' }
$script:NodeExe = if ($Runtime.NodeDir) { Join-Path $Runtime.NodeDir 'node.exe' } else { 'node' }
$script:NpmCmd = if ($Runtime.NpmCmd) { $Runtime.NpmCmd } else { 'npm.cmd' }

if ($Action -eq 'status') {
    Show-Status
    exit 0
}

if ($Action -eq 'stop' -or $Action -eq 'restart') {
    Write-Step '停止已有服务 ...'
    Stop-StartedProcesses
    Write-Ok '已停止'
}

if ($Action -eq 'stop') {
    Write-Info 'MySQL 服务保持运行，如需停止：mysql-service.cmd stop'
    exit 0
}

# ---------------- start / restart ----------------
$DbMode = 'mysql'
if ($Target -eq 'backend' -or $Target -eq 'all') {
    if (Import-LocalEnv) { Write-Ok '已加载数据库配置 config\local.env' }
    Test-Prerequisites -Which 'backend' -Runtime $Runtime
    $DbMode = Resolve-DatabaseMode
}
if ($Target -eq 'web' -or $Target -eq 'desktop') {
    Test-Prerequisites -Which $Target -Runtime $Runtime
}

try {
    if ($Target -eq 'all') {
        Start-BackendWithFallback -DbMode $DbMode -Detached
        Start-Frontend -Which 'web' -Detached
        Show-Summary -Which $Target -DbMode $script:EffectiveDb

        if (-not $NoBrowser) {
            Write-Step '正在打开浏览器 ...'
            Start-Process $WebUrl
        }

        Write-Host '  服务运行中，按 Ctrl+C 结束（或另开窗口执行 .\start.ps1 -Action stop）' -ForegroundColor Cyan
        Write-Host ''
        while ($true) {
            Start-Sleep -Seconds 3
            if (-not (Test-Listening -Port $BackendPort)) { Write-Fail '检测到后端已退出，请查看 logs\backend.log'; break }
            if (-not (Test-Listening -Port $WebPort)) { Write-Fail '检测到前端已退出，请查看 logs\web.log'; break }
        }
    } elseif ($Target -eq 'backend') {
        Show-Summary -Which $Target -DbMode $DbMode
        if ($Mode -eq 'prod') {
            Start-BackendWithFallback -DbMode $DbMode -Detached
            if (-not $NoBrowser) { Start-Process ('http://127.0.0.1:' + $BackendPort) }
            Write-Host '  服务运行中，按 Ctrl+C 结束（或另开窗口执行 .\start.ps1 -Action stop）' -ForegroundColor Cyan
            Write-Host ''
            while ($true) {
                Start-Sleep -Seconds 3
                if (-not (Test-Listening -Port $BackendPort)) { Write-Fail '检测到后端已退出，请查看 logs\backend.log'; break }
            }
        } else {
            Start-Backend -DbMode $DbMode | Out-Null
        }
    } elseif ($Target -eq 'web') {
        Show-Summary -Which $Target -DbMode $DbMode
        Start-Frontend -Which 'web'
    } else {
        Start-Frontend -Which 'desktop'
    }
} finally {
    if ($Target -eq 'all' -or ($Mode -eq 'prod' -and $Target -eq 'backend')) {
        Write-Host ''
        Write-Step '正在清理由本脚本启动的进程 ...'
        Stop-StartedProcesses
        Write-Ok '已退出'
    }
}
