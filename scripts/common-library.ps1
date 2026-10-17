#Requires -Version 5.1
<#
.SYNOPSIS
    qxfly-image-host 脚本公共库：所有 .ps1 统一 dot-source 本文件。
.DESCRIPTION
    这里集中了各脚本反复需要的、且踩过坑的能力：
      - 统一输出（Write-Step / Write-Ok / Write-Warn / Write-Fail / Write-Info / Write-Banner）
      - 进度日志 + 顶层 trap：任何异常都会落盘并带行号报错，不再出现「静默退出、退出码 0」
      - 编码安全的复制/删除：不通过管道捕获外部程序输出（那会让脚本被环境终止），并带重试
      - Stop-LockingJar：构建/打包前停掉占用 jar 的后端进程
      - 端口检查：检查/等待端口、查出占用进程
      - 脚本自检：.cmd 必须纯 ASCII、.ps1 必须有 UTF-8 BOM、语法必须能过解析器

  注意：编码红线——.cmd 一律纯 ASCII（cmd 按当前代码页解析，中文会被拆坏并执行出垃圾命令）；
  .ps1 一律保留 UTF-8 BOM（没有 BOM 时 PowerShell 5.1 按 ANSI 读中文，会吞掉后面的代码）。
#>

# ------------------------------------------------------------------ 统一输出
function Write-Banner {
    param([string]$Title)
    Write-Host ''
    Write-Host '  ============================================================' -ForegroundColor DarkCyan
    Write-Host ('     ' + $Title) -ForegroundColor White
    Write-Host '  ============================================================' -ForegroundColor DarkCyan
}
function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

# 外部命令的退出码不该被 $ErrorActionPreference='Stop' 变成异常（例如 robocopy 返回 1 表示成功）
function Invoke-Native {
    param([Parameter(Mandatory = $true)][scriptblock]$Script)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return & $Script } finally { $ErrorActionPreference = $previous }
}

# ------------------------------------------------------------------ 项目路径
# 脚本统一放在 scripts\ 下，所以项目根 = 脚本目录的上一级。
function Get-ProjectRoot {
    param([string]$ScriptRoot)
    $base = $ScriptRoot
    if (-not $base) { $base = $PSScriptRoot }
    if (-not $base) { $base = Split-Path -Parent $MyInvocation.MyCommand.Definition }
    return (Split-Path -Parent $base)
}

# ------------------------------------------------------------------ 进度日志与 trap
$script:__commonProgressLog = ''

function Initialize-ProgressLog {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$LogDir
    )
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
    $script:__commonProgressLog = Join-Path $LogDir ($Name + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
    Write-Progress-Log 'START'
    return $script:__commonProgressLog
}

function Get-ProgressLogPath { return $script:__commonProgressLog }

function Write-Progress-Log {
    param([string]$Text)
    if (-not $script:__commonProgressLog) { return }
    try {
        Add-Content -LiteralPath $script:__commonProgressLog -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  ' + $Text) -Encoding UTF8
    } catch { }
}

# 顶层 trap：dot-source 后对调用方脚本生效。异常一律写日志 + 报错 + 非零退出。
trap {
    $msg = $_.Exception.Message
    Write-Progress-Log ('ERROR ' + $_.Exception.GetType().Name + ': ' + $msg)
    Write-Progress-Log ('AT ' + $_.InvocationInfo.PositionMessage)
    Write-Fail ('脚本异常：' + $msg)
    Write-Info ('位置：' + ($_.InvocationInfo.PositionMessage -replace '\s+', ' '))
    if ($script:__commonProgressLog) { Write-Info ('进度日志：' + $script:__commonProgressLog) }
    exit 1
}

# ------------------------------------------------------------------ 编码安全的复制与删除
# 不用 cmd.exe / robocopy 的管道捕获输出：在本项目环境下「捕获外部程序输出」会让脚本
# 中途被终止（表现为静默退出）。删除用 rmdir（等删除真正落地，避免 delete-pending 导致的
# Access denied），复制用 robocopy（对 ucrtbase/msvcp140 这类运行库更稳）并带重试与兜底。
function Remove-IfExists {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return }
    foreach ($attempt in 1..3) {
        try {
            Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'rmdir', '/s', '/q', ('"' + $Path + '"')) -Wait -WindowStyle Hidden | Out-Null
        } catch { }
        if (Test-Path $Path) {
            try { Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Stop } catch { }
        }
        if (-not (Test-Path $Path)) { return }
        Start-Sleep -Seconds 2
    }
    Write-Fail ('无法删除目录（里面多半有文件被占用）：' + $Path)
    Write-Info '请关闭占用它的程序（本项目后端 / 解压工具 / 杀毒扫描）后重试'
    exit 1
}

function Copy-Tree {
    param([string]$From, [string]$To)
    if (-not (Test-Path $From)) { Write-Warn ('跳过不存在的路径：' + $From); return }
    $parent = Split-Path -Parent $To
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    if (Test-Path $To) { Remove-IfExists $To }

    $isDir = (Get-Item -LiteralPath $From).PSIsContainer
    if (-not $isDir) {
        Copy-Item -LiteralPath $From -Destination $To -Force
        return
    }

    $roboLog = Join-Path ([System.IO.Path]::GetTempPath()) ('qxfly-robocopy-' + [guid]::NewGuid().ToString('N') + '.log')
    $code = 16
    foreach ($attempt in 1..2) {
        try {
            # 路径必须显式加引号：Start-Process 的 -ArgumentList 只是用空格拼接，不会替你转义空格
            $proc = Start-Process -FilePath 'robocopy.exe' -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $roboLog -ArgumentList @(('"' + $From + '"'), ('"' + $To + '"'), '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:2', '/W:2')
            $code = $proc.ExitCode
        } catch { $code = 16 }
        if ($code -lt 8) { break }
        Write-Warn ('robocopy 第 ' + $attempt + ' 次返回 ' + $code + '，稍后重试')
        Start-Sleep -Seconds (3 * $attempt)
    }
    Remove-Item -LiteralPath $roboLog -Force -ErrorAction SilentlyContinue

    if ($code -ge 8) {
        if (-not (Test-Path $To)) { New-Item -ItemType Directory -Force -Path $To | Out-Null }
        $lastError = $null
        foreach ($attempt in 1..3) {
            try {
                # 按内容复制：目标已存在时 Copy-Item -Recurse 会把源目录整个塞进去（win-x64\win-x64）
                Copy-Item -Path (Join-Path $From '*') -Destination $To -Recurse -Force -ErrorAction Stop
                $lastError = $null
                break
            } catch {
                $lastError = $_
                Write-Warn ('第 ' + $attempt + ' 次复制失败，稍后重试：' + $_.Exception.Message)
                Start-Sleep -Seconds (2 * $attempt)
            }
        }
        if ($lastError) {
            throw ('复制失败：' + $From + ' -> ' + $To + '（robocopy 退出码 ' + $code + '；' + $lastError.Exception.Message + '）')
        }
    }
}

# ------------------------------------------------------------------ 大批量复制
# 这台机器上复制几百 MB 的小文件树时，进程会被环境（AV/EDR 扫描）杀掉：
#   - 用 robocopy 拷整个目录：可靠（Linux 运行时归档一直成功）
#   - 用 Copy-Item 逐个文件拷：会死在 jdk\lib\modules 这种 125 MB 的大文件上
# 所以这里按「一级子目录」分批调 robocopy，每批拷完校验文件数，缺了就重跑该批
# （robocopy 跳过相同文件 = 天然续传）。最后总校验，数量不符直接抛错，绝不留残包。
function Copy-Robocopy {
    param([string]$From, [string]$To, [int]$Attempts = 2)
    $roboLog = Join-Path ([System.IO.Path]::GetTempPath()) ('qxfly-robocopy-' + [guid]::NewGuid().ToString('N') + '.log')
    $code = 16
    foreach ($attempt in 1..$Attempts) {
        try {
            # 路径必须显式加引号：Start-Process 的 -ArgumentList 只是空格拼接，不转义空格
            $proc = Start-Process -FilePath 'robocopy.exe' -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput $roboLog -ArgumentList @(('"' + $From + '"'), ('"' + $To + '"'), '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP', '/R:1', '/W:1')
            $code = $proc.ExitCode
        } catch { $code = 16 }
        if ($code -lt 8) { break }
        Start-Sleep -Seconds (2 * $attempt)
    }
    Remove-Item -LiteralPath $roboLog -Force -ErrorAction SilentlyContinue
    return $code
}

function Copy-TreeVerified {
    param(
        [Parameter(Mandatory = $true)][string]$From,
        [Parameter(Mandatory = $true)][string]$To,
        [int]$Rounds = 3
    )
    if (-not (Test-Path $From)) { Write-Warn ('跳过不存在的路径：' + $From); return $true }
    $srcFiles = @(Get-ChildItem -LiteralPath $From -Recurse -File -ErrorAction SilentlyContinue)
    $srcCount = $srcFiles.Count
    $srcBytes = ($srcFiles | Measure-Object -Property Length -Sum).Sum
    Write-Info ('复制 ' + $srcCount + ' 个文件 / ' + [math]::Round($srcBytes / 1MB, 1) + ' MB → ' + $To)
    New-Item -ItemType Directory -Force -Path $To | Out-Null

    # 分批单位：一级子目录（jdk / node ...），根目录的散文件单独一批
    $batches = @()
    $rootFiles = @($srcFiles | Where-Object { $_.DirectoryName -eq $From })
    if ($rootFiles.Count -gt 0) { $batches += , @('', $rootFiles.Count) }
    foreach ($dir in @(Get-ChildItem -LiteralPath $From -Directory -ErrorAction SilentlyContinue)) {
        $count = @($srcFiles | Where-Object { $_.FullName.StartsWith($dir.FullName + '\') }).Count
        if ($count -gt 0) { $batches += , @($dir.Name, $count) }
    }

    foreach ($batch in $batches) {
        $name = $batch[0]
        $batchSrc = if ($name) { Join-Path $From $name } else { $From }
        $batchDst = if ($name) { Join-Path $To $name } else { $To }
        $label = if ($name) { $name } else { '(根目录文件)' }
        for ($attempt = 1; $attempt -le $Rounds; $attempt++) {
            $code = Copy-Robocopy -From $batchSrc -To $batchDst
            $want = $batch[1]
            $got = @(Get-ChildItem -LiteralPath $batchDst -Recurse -File -ErrorAction SilentlyContinue).Count
            if ($got -ge $want) { Write-Info ('  批次 ' + $label + ' 完成（' + $got + ' 个文件，robocopy=' + $code + '）'); break }
            Write-Warn ('  批次 ' + $label + ' 第 ' + $attempt + ' 次后缺 ' + ($want - $got) + ' 个，重跑续传')
            Start-Sleep -Seconds (2 * $attempt)
        }
    }

    $dstCount = @(Get-ChildItem -LiteralPath $To -Recurse -File -ErrorAction SilentlyContinue).Count
    if ($dstCount -ne $srcCount) {
        throw ('复制不完整：源 ' + $srcCount + ' 个文件，目标只有 ' + $dstCount + ' 个（' + $To + '）')
    }
    Write-Ok ('复制完成并校验通过：' + $dstCount + ' 个文件')
    return $true
}

# ------------------------------------------------------------------ 进程与端口
# 构建/打包前先停掉「正在运行的本项目后端」：它占着 target\*.jar 会让 clean / 打包失败。
function Stop-LockingJar {
    param([string[]]$JarPaths)
    $paths = @($JarPaths | Where-Object { $_ -and (Test-Path $_) })
    if ($paths.Count -eq 0) { return 0 }
    $stopped = 0
    try {
        $procs = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction SilentlyContinue |
            Where-Object {
                $cl = $_.CommandLine
                if (-not $cl) { return $false }
                foreach ($p in $paths) { if ($cl.IndexOf($p, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true } }
                $false
            })
        foreach ($proc in $procs) {
            try { Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop; $stopped++ } catch { }
        }
    } catch { }
    if ($stopped -gt 0) {
        Write-Warn ('已停止 ' + $stopped + ' 个占用 jar 的进程（它们会让 clean / 打包失败）')
        Start-Sleep -Milliseconds 1200
    }
    foreach ($p in $paths) {
        try {
            $probe = $p + '.lockprobe'
            [System.IO.File]::Move($p, $probe)
            [System.IO.File]::Move($probe, $p)
        } catch {
            Write-Fail ('jar 仍被占用：' + $p)
            Write-Info '请先关闭占用它的程序（本项目后端 / java -jar）后重试'
            exit 1
        }
    }
    return $stopped
}

function Test-Port {
    param([int]$Port)
    if ($Port -le 0) { return $false }
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne(300)
        if ($ok -and $client.Connected) { $client.Close(); return $true }
        $client.Close()
        return $false
    } catch { return $false }
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port -Port $Port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Get-PortOwner {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($conn) {
            $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
            if ($proc) { return ($proc.ProcessName + '(PID ' + $proc.Id + ')') }
            return ('PID ' + $conn.OwningProcess)
        }
    } catch { }
    return $null
}

function Assert-Command {
    param([string]$Name, [string]$Hint)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-Fail ('缺少命令：' + $Name)
        if ($Hint) { Write-Info $Hint }
        exit 1
    }
}

# ------------------------------------------------------------------ 脚本自检
# 两条编码红线 + 语法校验，一条命令查出所有问题（也可放进 CI）。
function Test-ScriptHealth {
    param([Parameter(Mandatory = $true)][string]$Root)
    $problems = @()
    $cmds = @(Get-ChildItem -Path $Root -Filter '*.cmd' -File -ErrorAction SilentlyContinue)
    $cmds += @(Get-ChildItem -Path (Join-Path $Root 'scripts') -Filter '*.cmd' -File -ErrorAction SilentlyContinue)
    foreach ($file in $cmds) {
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $nonAscii = @($bytes | Where-Object { $_ -gt 0x7F }).Count
        if ($nonAscii -gt 0) {
            $problems += ($file.Name + '：含 ' + $nonAscii + ' 个非 ASCII 字节（.cmd 必须纯 ASCII，否则 cmd 会按代码页解析错乱）')
        }
        if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            $problems += ($file.Name + '：含 UTF-8 BOM（.cmd 不能有 BOM，首行会被破坏）')
        }
    }
    $scripts = @(Get-ChildItem -Path $Root -Filter '*.ps1' -File -ErrorAction SilentlyContinue)
    $scripts += @(Get-ChildItem -Path (Join-Path $Root 'scripts') -Filter '*.ps1' -File -ErrorAction SilentlyContinue)
    foreach ($file in $scripts) {
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        if (-not $hasBom) {
            $problems += ($file.Name + '：缺少 UTF-8 BOM（没有 BOM 时 PowerShell 5.1 会按 ANSI 读中文，可能吞掉后续代码）')
        }
        $parseErrors = $null
        [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$null, [ref]$parseErrors) | Out-Null
        if ($parseErrors -and $parseErrors.Count -gt 0) {
            foreach ($e in ($parseErrors | Select-Object -First 3)) {
                $problems += ($file.Name + '：语法错误（第 ' + $e.Extent.StartLineNumber + ' 行）' + $e.Message)
            }
        }
    }
    return $problems
}

