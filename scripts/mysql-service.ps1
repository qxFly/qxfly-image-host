#Requires -Version 5.1
<#
.SYNOPSIS
    PicHost 数据库管家：把本机 MySQL 注册成 Windows 服务，并支持用 cmd 启停、设开机自启、配置 PATH、初始化业务库。

.DESCRIPTION
    默认安装位置：C:\Program Files\MySQL\MySQL Server 8.0
    脚本自动从已有服务 / 常见目录探测 MySQL，然后提供：

        status            查看服务状态、端口、PATH、连接配置（默认动作）
        install           注册 Windows 服务（已存在则跳过），并设为开机自启
        start|stop|restart
        autostart on|off  设置开机自启
        path add|machine|remove   把 MySQL bin 写进 PATH，让 cmd 直接能用 mysql
        init              连接并初始化业务库，把连接信息写进 config/local.env
        console           打开 mysql 交互命令行

.EXAMPLE
    .\mysql-service.cmd status
    .\mysql-service.cmd install
    .\mysql-service.cmd path add
    .\mysql-service.cmd init -User root -Password 你的root密码
    .\mysql-service.cmd console
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('status', 'install', 'start', 'stop', 'restart', 'autostart', 'path', 'init', 'console')]
    [string]$Action = 'status',

    [Parameter(Position = 1)]
    [string]$Value = '',

    [string]$User = 'root',
    [string]$Password = '',
    [string]$Database = 'pichost',
    [string]$AppUser = 'pichost',
    [string]$AppPassword = '',
    [switch]$NoCreate,
    [switch]$NoElevate,
    [string]$MySqlHome = ''
)

$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent $PSScriptRoot
$ConfigDir = Join-Path $Root 'config'
$EnvFile   = Join-Path $ConfigDir 'local.env'
$ServiceDefault = 'MySQL80'

function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

function Test-Admin {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

# 需要管理员时自动提权，并原样转发参数
function Invoke-Elevated {
    param([string]$Reason)
    if (Test-Admin) { return $true }
    if ($NoElevate) { return $false }
    Write-Warn ($Reason + '：需要管理员权限，即将弹出 UAC 提权窗口 ...')
    $forward = @()
    foreach ($key in $PSBoundParameters.Keys) {
        $val = $PSBoundParameters[$key]
        if ($val -is [switch]) { if ($val.IsPresent) { $forward += ('-' + $key) } }
        else { $forward += ('-' + $key); $forward += [string]$val }
    }
    $forward += '-NoElevate'
    $argList = @('-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath) + $forward
    Start-Process -FilePath 'powershell.exe' -ArgumentList $argList -Verb RunAs -Wait
    Write-Info '提权窗口已结束；重新执行 status 可查看结果'
    return $false
}

# ---------------------------------------------------------------- 探测
function Find-MySql {
    $found = @{ ServiceName = $null; BinDir = $null; MyIni = $null; Home = $null }

    $candidates = @()
    if ($MySqlHome) { $candidates += $MySqlHome }
    $candidates += 'C:\Program Files\MySQL\MySQL Server 8.0'
    $candidates += 'C:\Program Files\MySQL\MySQL Server 8.4'
    $candidates += 'C:\Program Files\MySQL\MySQL Server 5.7'
    foreach ($d in (Get-ChildItem 'C:\Program Files\MySQL' -Directory -ErrorAction SilentlyContinue)) { $candidates += $d.FullName }
    foreach ($d in (Get-ChildItem 'C:\Program Files (x86)\MySQL' -Directory -ErrorAction SilentlyContinue)) { $candidates += $d.FullName }

    foreach ($homeDir in ($candidates | Select-Object -Unique)) {
        if (-not $homeDir) { continue }
        $bin = Join-Path $homeDir 'bin'
        if (Test-Path (Join-Path $bin 'mysqld.exe')) {
            $found.Home = $homeDir
            $found.BinDir = $bin
            break
        }
    }

    $svc = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object { $_.PathName -like '*mysqld.exe*' } | Select-Object -First 1
    if ($svc) {
        $found.ServiceName = $svc.Name
        if (-not $found.BinDir -and $svc.PathName -match '"?([A-Za-z]:\\[^"]*?)mysqld\.exe') {
            $found.BinDir = $Matches[1].TrimEnd('\')
            $found.Home = Split-Path -Parent $found.BinDir
        }
        if ($svc.PathName -match '--defaults-file="?([^"]+?\.ini)"?') { $found.MyIni = $Matches[1] }
    }
    if (-not $found.ServiceName) { $found.ServiceName = $ServiceDefault }

    if (-not $found.MyIni) {
        foreach ($p in @('C:\ProgramData\MySQL\MySQL Server 8.0\my.ini', 'C:\ProgramData\MySQL\MySQL Server 8.4\my.ini')) {
            if (Test-Path $p) { $found.MyIni = $p; break }
        }
    }
    return $found
}

$MySql     = Find-MySql
$MysqlExe  = if ($MySql.BinDir) { Join-Path $MySql.BinDir 'mysql.exe' } else { $null }
$MysqldExe = if ($MySql.BinDir) { Join-Path $MySql.BinDir 'mysqld.exe' } else { $null }

function Get-ServiceInfo {
    if (-not $MySql.ServiceName) { return $null }
    return Get-CimInstance Win32_Service -Filter ("Name='" + $MySql.ServiceName + "'") -ErrorAction SilentlyContinue
}

function Test-MySqlOnPath {
    if (-not $MySql.BinDir) { return $null }
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($machinePath -and $machinePath -like ('*' + $MySql.BinDir + '*')) { return 'Machine' }
    if ($userPath -and $userPath -like ('*' + $MySql.BinDir + '*')) { return 'User' }
    return $null
}

# 口令走 MYSQL_PWD，避免出现在命令行参数与进程列表里
function Invoke-Sql {
    param([string]$Sql, [string]$DbUser, [string]$DbPassword, [string]$DbName = '')
    if (-not $MysqlExe) { throw '未找到 mysql.exe' }
    $previous = $env:MYSQL_PWD
    $env:MYSQL_PWD = $DbPassword
    try {
        $cliArgs = @('-u', $DbUser, '--connect-timeout=8', '-N', '-B')
        if ($DbName) { $cliArgs += $DbName }
        $cliArgs += @('-e', $Sql)
        $output = & $MysqlExe @cliArgs 2>&1
        $code = $LASTEXITCODE
        return @{ Code = $code; Output = ($output -join [char]10) }
    } finally {
        $env:MYSQL_PWD = $previous
    }
}

function Read-EnvFile {
    $map = @{}
    if (-not (Test-Path $EnvFile)) { return $map }
    foreach ($line in (Get-Content $EnvFile -Encoding UTF8)) {
        if ($line -match '^\s*([A-Z_]+)\s*=\s*(.*)$') { $map[$Matches[1]] = $Matches[2].Trim() }
    }
    return $map
}

# ---------------------------------------------------------------- 动作
function Show-Status {
    Write-Host ''
    Write-Host '  ============================================================' -ForegroundColor DarkCyan
    Write-Host '     PicHost 数据库状态' -ForegroundColor White
    Write-Host '  ============================================================' -ForegroundColor DarkCyan

    if ($MySql.Home) { Write-Host ('  安装目录 : ' + $MySql.Home) -ForegroundColor Gray }
    else { Write-Host '  安装目录 : 未找到（可用 -MySqlHome 指定）' -ForegroundColor Yellow }
    if ($MySql.MyIni) { Write-Host ('  配置文件 : ' + $MySql.MyIni) -ForegroundColor Gray }

    $svc = Get-ServiceInfo
    if ($svc) {
        Write-Host ('  服务名称 : ' + $svc.Name) -ForegroundColor Gray
        if ($svc.State -eq 'Running') { Write-Host ('  运行状态 : ' + $svc.State) -ForegroundColor Green }
        else { Write-Host ('  运行状态 : ' + $svc.State) -ForegroundColor Yellow }
        if ($svc.StartMode -eq 'Auto') { Write-Host ('  启动方式 : ' + $svc.StartMode + '（开机自启）') -ForegroundColor Green }
        else { Write-Host ('  启动方式 : ' + $svc.StartMode + '（非开机自启）') -ForegroundColor Yellow }
        Write-Host ('  运行账户 : ' + $svc.StartName) -ForegroundColor Gray
    } else {
        Write-Host ('  服务名称 : ' + $MySql.ServiceName + '（尚未注册）') -ForegroundColor Yellow
        Write-Info '执行 .\mysql-service.cmd install 可注册并设为开机自启'
    }

    if (@(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue).Count -gt 0) {
        Write-Host '  端口监听 : 3306 已监听' -ForegroundColor Green
    } else {
        Write-Host '  端口监听 : 3306 未监听' -ForegroundColor Yellow
    }

    $scope = Test-MySqlOnPath
    if ($scope) {
        Write-Host ('  cmd 调用 : mysql 已加入 ' + $scope + ' PATH（新开 cmd 窗口即可直接使用）') -ForegroundColor Green
    } else {
        Write-Host '  cmd 调用 : mysql 未加入 PATH' -ForegroundColor Yellow
        Write-Info '执行 .\mysql-service.cmd path add 后，新开 cmd 即可直接使用 mysql'
        Write-Info '也可直接使用项目根目录的 mysql.cmd（无需改动 PATH）'
    }

    $envMap = Read-EnvFile
    if ($envMap.Count -gt 0) {
        Write-Host ('  连接配置 : ' + $EnvFile) -ForegroundColor Gray
        $test = Invoke-Sql -Sql 'SELECT 1;' -DbUser $envMap['MYSQL_USER'] -DbPassword $envMap['MYSQL_PASSWORD'] -DbName $envMap['MYSQL_DATABASE']
        if ($test.Code -eq 0) {
            Write-Host ('  连接测试 : 通过（账号 ' + $envMap['MYSQL_USER'] + '，库 ' + $envMap['MYSQL_DATABASE'] + '）') -ForegroundColor Green
        } else {
            Write-Host '  连接测试 : 失败' -ForegroundColor Red
            ($test.Output -split [char]10) | Select-Object -First 2 | ForEach-Object { Write-Info $_ }
        }
    } else {
        Write-Host '  连接配置 : 尚未生成（执行 init 生成）' -ForegroundColor Yellow
    }
    Write-Host ''
}

function Invoke-Install {
    if (-not (Test-Admin)) { Invoke-Elevated -Reason '注册 Windows 服务'; return }
    if (-not $MysqldExe) { Write-Fail '未找到 mysqld.exe'; return }

    $svc = Get-ServiceInfo
    if ($svc) {
        Write-Ok ('服务已存在：' + $svc.Name + '，跳过注册')
    } else {
        Write-Step ('注册服务 ' + $MySql.ServiceName + ' ...')
        $cliArgs = @('--install', $MySql.ServiceName)
        if ($MySql.MyIni) { $cliArgs += ('--defaults-file=' + $MySql.MyIni) }
        & $MysqldExe @cliArgs
        if ($LASTEXITCODE -ne 0) { Write-Fail ('注册失败，退出码 ' + $LASTEXITCODE); return }
        Write-Ok '服务注册完成'
    }

    Write-Step '设置开机自启并配置异常自动重启 ...'
    & sc.exe config $MySql.ServiceName start= auto | Out-Null
    & sc.exe failure $MySql.ServiceName reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null

    $svc = Get-ServiceInfo
    if ($svc.State -ne 'Running') {
        Write-Step '启动服务 ...'
        Start-Service -Name $MySql.ServiceName
        Start-Sleep -Seconds 3
    }
    $svc = Get-ServiceInfo
    Write-Ok ('完成：' + $svc.State + ' / ' + $svc.StartMode)
}

function Invoke-ServiceAction {
    param([string]$Verb)
    $svc = Get-ServiceInfo
    if (-not $svc) { Write-Fail ('服务 ' + $MySql.ServiceName + ' 未注册，请先执行 install'); return }
    if (-not (Test-Admin)) { Invoke-Elevated -Reason ($Verb + ' 服务'); return }
    if ($Verb -eq 'start')   { Start-Service -Name $MySql.ServiceName }
    if ($Verb -eq 'stop')    { Stop-Service -Name $MySql.ServiceName -Force }
    if ($Verb -eq 'restart') { Restart-Service -Name $MySql.ServiceName -Force }
    Start-Sleep -Seconds 2
    $svc = Get-ServiceInfo
    Write-Ok ('服务 ' + $MySql.ServiceName + ' 当前状态：' + $svc.State)
}

function Invoke-Autostart {
    $svc = Get-ServiceInfo
    if (-not $svc) { Write-Fail '服务未注册'; return }
    if (-not (Test-Admin)) { Invoke-Elevated -Reason '设置开机自启'; return }
    $mode = 'auto'
    if ($Value -eq 'off') { $mode = 'demand' }
    & sc.exe config $MySql.ServiceName start= $mode | Out-Null
    $svc = Get-ServiceInfo
    Write-Ok ('启动方式已设为：' + $svc.StartMode)
}

function Invoke-Path {
    if (-not $MySql.BinDir) { Write-Fail '未找到 MySQL bin 目录'; return }

    if ($Value -eq 'machine') {
        if (-not (Test-Admin)) { Invoke-Elevated -Reason '修改系统 PATH'; return }
        Set-PathEntry -Scope 'Machine'
        return
    }
    if ($Value -eq 'remove') {
        Set-PathEntry -Scope 'User' -Remove
        return
    }
    Set-PathEntry -Scope 'User'
}

function Set-PathEntry {
    param([string]$Scope, [switch]$Remove)
    $current = [Environment]::GetEnvironmentVariable('Path', $Scope)
    if (-not $current) { $current = '' }
    $parts = @($current -split ';' | Where-Object { $_ -ne '' })
    $target = $MySql.BinDir.TrimEnd('\')
    $kept = @($parts | Where-Object { $_.TrimEnd('\') -ne $target })

    if ($Remove) {
        try {
            [Environment]::SetEnvironmentVariable('Path', ($kept -join ';'), $Scope)
        } catch {
            Write-Fail ('修改 ' + $Scope + ' PATH 失败：' + $_.Exception.Message)
            Write-Info '可改用管理员身份运行；不改 PATH 也能用 mysql.cmd'
            return
        }
        Write-Ok ('已从 ' + $Scope + ' PATH 移除：' + $MySql.BinDir)
        return
    }
    if ($kept.Count -eq $parts.Count) {
        # 原有条目里已包含同一个路径（仅大小写/斜杠差异），直接确认
        $already = $parts | Where-Object { $_.TrimEnd('\') -ieq $target }
        if ($already) {
            Write-Ok ('已存在于 ' + $Scope + ' PATH：' + $MySql.BinDir)
            return
        }
    }
    $newPath = (@($kept + $MySql.BinDir) -join ';')
    try {
        [Environment]::SetEnvironmentVariable('Path', $newPath, $Scope)
    } catch {
        Write-Fail ('写入 ' + $Scope + ' PATH 失败：' + $_.Exception.Message)
        Write-Info '可改用管理员身份运行：.mysql-service.cmd path machine'
        Write-Info '不改 PATH 也能用：项目根目录的 mysql.cmd 可直接调用 mysql'
        return
    }
    Write-Ok ('已写入 ' + $Scope + ' PATH：' + $MySql.BinDir)
    Write-Info '新开一个 cmd 窗口，然后执行：mysql --version'
}

function Invoke-Console {
    if (-not $MysqlExe) { Write-Fail '未找到 mysql.exe'; return }
    $cliArgs = @('-u', $User)
    if ($Password) { $env:MYSQL_PWD = $Password }
    Write-Info '进入 mysql 命令行，输入 exit 退出'
    & $MysqlExe @cliArgs
    $env:MYSQL_PWD = $null
}

function Invoke-Init {
    if (-not $MysqlExe) { Write-Fail '未找到 mysql.exe'; return }
    if (-not $Password) {
        Write-Fail '请提供数据库口令，例如：'
        Write-Info '.\mysql-service.cmd init -User root -Password 你的密码'
        return
    }

    Write-Step '测试数据库连接 ...'
    $probe = Invoke-Sql -Sql 'SELECT VERSION();' -DbUser $User -DbPassword $Password
    if ($probe.Code -ne 0) {
        Write-Fail '连接失败：'
        ($probe.Output -split [char]10) | Select-Object -First 4 | ForEach-Object { Write-Info $_ }
        Write-Info '请确认口令是否正确；若忘记 root 口令需先重置'
        return
    }
    Write-Ok ('连接成功，MySQL 版本 ' + $probe.Output.Trim())

    if (-not $NoCreate) {
        if (-not $AppPassword) {
            $bytes = New-Object byte[] 18
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
            $AppPassword = (([Convert]::ToBase64String($bytes)) -replace '[^A-Za-z0-9]', '')
        }
        Write-Step ('创建业务库 ' + $Database + ' 与账号 ' + $AppUser + ' ...')
        $sql = @(
            ('CREATE DATABASE IF NOT EXISTS ' + $Database + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;'),
            ("CREATE USER IF NOT EXISTS '" + $AppUser + "'@'%' IDENTIFIED BY '" + $AppPassword + "';"),
            ("ALTER USER '" + $AppUser + "'@'%' IDENTIFIED BY '" + $AppPassword + "';"),
            ("CREATE USER IF NOT EXISTS '" + $AppUser + "'@'localhost' IDENTIFIED BY '" + $AppPassword + "';"),
            ("ALTER USER '" + $AppUser + "'@'localhost' IDENTIFIED BY '" + $AppPassword + "';"),
            ("GRANT ALL PRIVILEGES ON " + $Database + ".* TO '" + $AppUser + "'@'%';"),
            ("GRANT ALL PRIVILEGES ON " + $Database + ".* TO '" + $AppUser + "'@'localhost';"),
            'FLUSH PRIVILEGES;'
        ) -join ' '
        $created = Invoke-Sql -Sql $sql -DbUser $User -DbPassword $Password
        if ($created.Code -ne 0) { Write-Fail ('创建失败：' + $created.Output); return }
        Write-Ok '业务库与账号已就绪'
    }

    if (-not (Test-Path $ConfigDir)) { New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null }
    $finalUser = $AppUser
    $finalPassword = $AppPassword
    if ($NoCreate) { $finalUser = $User; $finalPassword = $Password }

    $lines = @(
        '# PicHost 数据库连接配置（由 mysql-service.ps1 生成，已被 .gitignore 忽略）',
        'MYSQL_HOST=127.0.0.1',
        'MYSQL_PORT=3306',
        ('MYSQL_DATABASE=' + $Database),
        ('MYSQL_USER=' + $finalUser),
        ('MYSQL_PASSWORD=' + $finalPassword)
    )
    [System.IO.File]::WriteAllLines($EnvFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Ok ('连接配置已写入：' + $EnvFile)

    $check = Invoke-Sql -Sql 'SELECT 1;' -DbUser $finalUser -DbPassword $finalPassword -DbName $Database
    if ($check.Code -eq 0) { Write-Ok '业务账号连接测试通过' }
    else { Write-Warn ('业务账号连接测试失败：' + $check.Output) }

    Write-Host ''
    Write-Info '现在可直接启动系统：.\start.ps1'
}

# ---------------------------------------------------------------- 入口
switch ($Action) {
    'status'    { Show-Status }
    'install'   { Invoke-Install }
    'start'     { Invoke-ServiceAction -Verb 'start' }
    'stop'      { Invoke-ServiceAction -Verb 'stop' }
    'restart'   { Invoke-ServiceAction -Verb 'restart' }
    'autostart' { Invoke-Autostart }
    'path'      { Invoke-Path }
    'console'   { Invoke-Console }
    'init'      { Invoke-Init }
}
