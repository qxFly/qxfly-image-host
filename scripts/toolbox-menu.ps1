#Requires -Version 5.1
<#
.SYNOPSIS
    qxfly-image-host 工具箱：把打包、桌面客户端、运行时、数据库、诊断、清理等操作收进一个入口。
.DESCRIPTION
    两种用法：
      1) 交互菜单：toolbox.cmd            （不出参数时进菜单，按编号选择）
      2) 直接子命令（给自动化/CI 用）：
           toolbox.cmd status | start | stop | restart     启动相关（转发给 scripts\start-services.ps1）
           toolbox.cmd package [-Zip]                      打包完整发行包（Windows + Linux）
           toolbox.cmd package-server [-Runtime linux]     一键打服务端包（默认带 Windows+Linux 运行时，含冒烟测试）
           toolbox.cmd desktop                             打包 Tauri 桌面客户端
           toolbox.cmd runtime                             准备便携运行时（JDK + Node）
           toolbox.cmd db status|install|start|stop ...    数据库管家
           toolbox.cmd check                               脚本自检（.cmd 纯ASCII / .ps1 有BOM / 语法）
           toolbox.cmd diag                                一键诊断，产出诊断报告
           toolbox.cmd clean                               清理缓存与产物（先算体积再确认）
           toolbox.cmd config                              查看当前生效配置与访问地址
           toolbox.cmd logs [名称]                         查看日志
           toolbox.cmd deploy                              生成 nginx / systemd 部署模板
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Action = '',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$ScriptsDir = $PSScriptRoot
$Root       = Split-Path -Parent $PSScriptRoot
$ServerDir  = Join-Path $Root 'server'
$WebDir     = Join-Path $Root 'web'
$DistDir    = Join-Path $Root 'dist'
$ConfigDir  = Join-Path $Root 'config'
$EnvFile    = Join-Path $ConfigDir 'local.env'
$LogDir     = Join-Path $Root 'logs'

. (Join-Path $PSScriptRoot 'common-library.ps1')

function Get-EnvMap {
    $map = [ordered]@{}
    if (-not (Test-Path $EnvFile)) { return $map }
    foreach ($line in (Get-Content -LiteralPath $EnvFile -Encoding UTF8)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $idx = $text.IndexOf('=')
        if ($idx -le 0) { continue }
        $key = $text.Substring(0, $idx).Trim()
        $value = $text.Substring($idx + 1).Trim()
        $map[$key] = $value
    }
    return $map
}

function Get-EffectiveBackendPort {
    $map = Get-EnvMap
    if ($map.Contains('BACKEND_PORT') -and $map['BACKEND_PORT']) { return [int]$map['BACKEND_PORT'] }
    if ($map.Contains('SERVER_PORT') -and $map['SERVER_PORT']) { return [int]$map['SERVER_PORT'] }
    return 8080
}

function Show-Config {
    $map = Get-EnvMap
    Write-Banner '当前生效配置'
    Write-Info ('配置文件：' + $EnvFile + $(if (Test-Path $EnvFile) { '' } else { '（不存在，将使用默认值）' }))
    Write-Host ''
    $keys = @('BACKEND_PORT', 'WEB_PORT', 'JAVA_OPTS', 'PUBLIC_BASE_URL', 'LOCAL_STORAGE_DIR', 'LOCAL_STORAGE_PUBLIC_URL', 'THUMB_CACHE_DIR', 'THUMB_CACHE_MAX_SIZE', 'AUDIT_RETENTION_DAYS', 'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USER', 'STORAGE_CHECK_ENABLED')
    foreach ($k in $keys) {
        $v = if ($map.Contains($k)) { $map[$k] } else { '' }
        Write-Host ('    ' + $k.PadRight(26) + $(if ($v) { $v } else { '(未设置)' }))
    }
    $port = Get-EffectiveBackendPort
    Write-Host ''
    Write-Ok ('后端访问地址： http://127.0.0.1:' + $port)
    Write-Info ('局域网/公网： http://<服务器IP>:' + $port + '   （反代时请把 PUBLIC_BASE_URL 设为对外地址）')
}

function Show-Logs {
    param([string]$Name)
    Write-Banner '日志'
    if (-not (Test-Path $LogDir)) { Write-Warn '还没有 logs 目录'; return }
    $files = @(Get-ChildItem -Path $LogDir -Filter '*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)
    if ($files.Count -eq 0) { Write-Warn 'logs 目录里还没有日志文件'; return }
    Write-Host ''
    for ($i = 0; $i -lt $files.Count; $i++) {
        Write-Host ('    [' + ($i + 1) + '] ' + $files[$i].Name.PadRight(34) + [math]::Round($files[$i].Length / 1KB, 1).ToString().PadLeft(9) + ' KB   ' + $files[$i].LastWriteTime)
    }
    $target = $null
    if ($Name) {
        $target = $files | Where-Object { $_.Name -like ('*' + $Name + '*') } | Select-Object -First 1
        if (-not $target) { Write-Warn ('没有匹配的日志：' + $Name); return }
    } else {
        $target = $files[0]
        Write-Info '未指定名称，显示最新的一个（也可：toolbox.cmd logs backend）'
    }
    Write-Host ''
    Write-Ok ('最近 40 行：' + $target.Name)
    Get-Content -LiteralPath $target.FullName -Tail 40 | ForEach-Object { Write-Host ('    ' + $_) }
}

function Invoke-Check {
    Write-Banner '脚本自检（编码与语法）'
    $problems = Test-ScriptHealth -Root $Root
    if (-not $problems -or $problems.Count -eq 0) {
        Write-Ok '.cmd 均纯 ASCII、.ps1 均有 UTF-8 BOM、语法全部通过'
        return $true
    }
    Write-Fail ('发现 ' + $problems.Count + ' 个问题：')
    foreach ($p in $problems) { Write-Host ('    - ' + $p) -ForegroundColor Yellow }
    return $false
}

function Invoke-Diag {
    Write-Banner '一键诊断'
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('qxfly-image-host 诊断报告  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    $lines.Add('')
    $lines.Add('== 环境 ==')
    $lines.Add('PowerShell : ' + $PSVersionTable.PSVersion.ToString())
    $javaExe = Join-Path $Root 'runtime\win-x64\jdk\bin\java.exe'
    if (Test-Path $javaExe) { $lines.Add('JDK        : 便携 ' + $javaExe) } else { $lines.Add('JDK        : 未找到便携 JDK') }
    try { $lines.Add('JDK 版本   : ' + ((& $javaExe -version 2>&1 | Select-Object -First 1))) } catch { }
    $lines.Add('Python/Node: ' + $(if (Get-Command node -ErrorAction SilentlyContinue) { 'node ' + (& node -v) } else { '无' }))
    $lines.Add('')
    $lines.Add('== 端口 ==')
    $port = Get-EffectiveBackendPort
    $owner = Get-PortOwner -Port $port
    $lines.Add('后端端口 ' + $port + ' : ' + $(if ($owner) { '被占用 ' + $owner } else { '空闲' }))
    $webOwner = Get-PortOwner -Port 5173
    $lines.Add('前端端口 5173 : ' + $(if ($webOwner) { '被占用 ' + $webOwner } else { '空闲' }))
    $lines.Add('')
    $lines.Add('== 产物与数据 ==')
    foreach ($item in @(@('后端 jar', (Join-Path $ServerDir 'target\qxfly-image-host.jar')), @('前端构建', (Join-Path $WebDir 'dist\index.html')), @('本地图片目录 uploads', (Join-Path $Root 'uploads')), @('缩略图缓存 thumbs', (Join-Path $Root 'thumbs')), @('发行产物 dist', $DistDir))) {
        $p = $item[1]
        if (Test-Path $p) {
            $size = (Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
            $lines.Add(($item[0] + ' : ' + (Get-Item $p).LastWriteTime + '  ' + [math]::Round($size / 1MB, 1) + ' MB'))
        } else {
            $lines.Add(($item[0] + ' : 不存在'))
        }
    }
    try {
        $drive = (Get-Item $Root).PSDrive.Name
        $free = (Get-PSDrive -Name $drive).Free
        $lines.Add('磁盘剩余   : ' + [math]::Round($free / 1GB, 1) + ' GB (' + $drive + ':)')
    } catch { }
    $lines.Add('')
    $lines.Add('== 配置 ==')
    foreach ($kv in (Get-EnvMap).GetEnumerator()) { $lines.Add(('  ' + $kv.Key + '=' + $kv.Value)) }
    $lines.Add('')
    $lines.Add('== 最近的进度日志 ==')
    Get-ChildItem -Path $LogDir -Filter '*.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 5 | ForEach-Object { $lines.Add('  ' + $_.Name + '  ' + $_.LastWriteTime) }
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
    $out = Join-Path $LogDir ('diag-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
    $lines | Set-Content -LiteralPath $out -Encoding UTF8
    Write-Ok ('诊断报告已生成：' + $out)
    Write-Host ''
    $lines | Select-Object -First 24 | ForEach-Object { Write-Host ('    ' + $_) }
}

function Invoke-Clean {
    Write-Banner '清理（先算体积，再让你确认）'
    Write-Warn '绝不触碰：uploads\（图片实体）、config\local.env、数据库'
    $targets = @(
        @('缩略图缓存', (Join-Path $Root 'thumbs')),
        @('发行产物 dist', $DistDir),
        @('后端构建 target', (Join-Path $ServerDir 'target')),
        @('前端构建 web\dist', (Join-Path $WebDir 'dist'))
    )
    $total = 0
    $picked = @()
    Write-Host ''
    foreach ($t in $targets) {
        $p = $t[1]
        if (Test-Path $p) {
            $size = (Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
            if (-not $size) { $size = 0 }
            $total += $size
            $picked += $t
            Write-Host ('    ' + $t[0].PadRight(20) + [math]::Round($size / 1MB, 1).ToString().PadLeft(10) + ' MB   ' + $p)
        } else {
            Write-Host ('    ' + $t[0].PadRight(20) + '（不存在）')
        }
    }
    $logFiles = @(Get-ChildItem -Path $LogDir -Filter '*.log' -File -ErrorAction SilentlyContinue)
    if ($logFiles.Count -gt 0) {
        $logSize = ($logFiles | Measure-Object -Property Length -Sum).Sum
        Write-Host ('    ' + '日志文件'.PadRight(20) + [math]::Round($logSize / 1MB, 1).ToString().PadLeft(10) + ' MB   ' + $LogDir + '  (' + $logFiles.Count + ' 个)')
    }
    Write-Host ''
    Write-Info ('可释放约 ' + [math]::Round($total / 1MB, 1) + ' MB（不含日志）')
    if ($picked.Count -eq 0 -and $logFiles.Count -eq 0) { Write-Ok '没什么可清理的'; return }
    $answer = Read-Host '    确认清理请输入 yes（其他任何输入取消）'
    if ($answer -ne 'yes') { Write-Warn '已取消'; return }
    foreach ($t in $picked) { Remove-IfExists $t[1]; Write-Ok ('已清理：' + $t[0]) }
    foreach ($lf in $logFiles) { Remove-Item -LiteralPath $lf.FullName -Force -ErrorAction SilentlyContinue }
    Write-Ok '清理完成（下次启动/打包会重新生成）'
}
function Invoke-Deploy {
    Write-Banner '生成部署模板（nginx / systemd）'
    $map = Get-EnvMap
    $port = Get-EffectiveBackendPort
    $publicBase = if ($map.Contains('PUBLIC_BASE_URL')) { $map['PUBLIC_BASE_URL'] } else { '' }
    $outDir = Join-Path $DistDir 'deploy'
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }
    $installRoot = '/opt/qxfly-image-host'

    $nginx = @'
# qxfly-image-host —— nginx 反向代理示例（放到 /etc/nginx/conf.d/qxfly.conf）
# 用法：把 server_name 改成你的域名或服务器 IP，然后 nginx -t && systemctl reload nginx
server {
    listen 80;
    server_name __SERVER_NAME__;

    # 上传体积上限：必须 >= 应用里设置的单张上限（默认 30MB；改成“无上限”时要相应放大）
    client_max_body_size 2g;

    location / {
        proxy_pass http://127.0.0.1:__PORT__;
        proxy_http_version 1.1;

        # 生成链接的主机名与协议：不设会导致缩略图/本地存储直链指向错误地址
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # 上传是流式 + 大文件：别让 nginx 先整包落盘，也别太早超时
        proxy_request_buffering off;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    # /api、/thumb、/files 都走上面的 location /（jar 自己提供界面与接口）
    # 若想让 nginx 直接出静态文件，把 jar 里的 static/ 解出来另配 location（前端是 hash 路由，无需 try_files 回退）
}
'@

    $unit = @'
[Unit]
Description=qxfly-image-host
After=network.target mysql.service

[Service]
Type=simple
User=qxfly
# WorkingDirectory 决定 uploads/ thumbs/ logs/ 的相对位置，务必设置正确
WorkingDirectory=__ROOT__
EnvironmentFile=__ROOT__/config/local.env
__PUBLIC_BASE_ENV__ExecStart=__ROOT__/runtime/linux-x64/jdk/bin/java -Xms256m -Xmx1g -XX:+UseG1GC -jar __ROOT__/qxfly-image-host.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
'@

    $readme = @'
部署到 Ubuntu 的步骤（qcxfly-image-host）

1) 目录：把服务端内容放到 __ROOT__/
     qxfly-image-host.jar            ← 唯一核心产物（界面已打进 jar）
     config/local.env                ← 端口/数据库/PUBLIC_BASE_URL
     uploads/  thumbs/  logs/        ← 运行时生成（uploads 是图片实体，必须备份）
     runtime/linux-x64/jdk/          ← 便携 JDK（或改用系统 openjdk-17-jre-headless）

2) 不需要上传服务器的内容：
     web/（前端源码，界面已在 jar 里）、web/src-tauri（桌面客户端）、
     server/src 与 mvnw（服务器只跑 jar）、scripts/*.ps1（Windows 打包脚本）、
     runtime/win-x64、.tools/、.github/、dist/desktop/
     runtime/_cache 的压缩包在解压后可删（重装才需要）

3) systemd（推荐替代前台 start.sh）：
     把 qxfly-image-host.service 拷到 /etc/systemd/system/
     systemctl daemon-reload && systemctl enable --now qxfly-image-host
     日志：journalctl -u qxfly-image-host -f

4) nginx 反向代理：
     把 qxfly.conf 拷到 /etc/nginx/conf.d/，改 server_name 后 nginx -t && systemctl reload nginx
     注意 client_max_body_size 要 >= 应用的单张上限

5) 反代时必须设置 PUBLIC_BASE_URL（config/local.env），否则生成的链接会指向错误地址：
     反代： PUBLIC_BASE_URL=https://你的域名      （或 http://服务器IP）
     直连： PUBLIC_BASE_URL=http://服务器IP:__PORT__

6) 只监听本机（配合反代）：在 config/local.env 里加 SERVER_ADDRESS=127.0.0.1
'@

    $serverName = if ($publicBase) { ($publicBase -replace '^https?://', '') -replace '/.*$', '' } else { 'your-domain-or-server-ip' }
    $publicEnv = if ($publicBase) { ('Environment=PUBLIC_BASE_URL=' + $publicBase + [Environment]::NewLine) } else { '' }

    $nginxPath = Join-Path $outDir 'qxfly.conf'
    $unitPath = Join-Path $outDir 'qxfly-image-host.service'
    $readmePath = Join-Path $outDir 'DEPLOY-README.txt'
    $nginx.Replace('__SERVER_NAME__', $serverName).Replace('__PORT__', [string]$port) | Set-Content -LiteralPath $nginxPath -Encoding UTF8
    $unit.Replace('__ROOT__', $installRoot).Replace('__PUBLIC_BASE_ENV__', $publicEnv) | Set-Content -LiteralPath $unitPath -Encoding UTF8
    $readme.Replace('__ROOT__', $installRoot).Replace('__PORT__', [string]$port) | Set-Content -LiteralPath $readmePath -Encoding UTF8

    Write-Ok ('已生成到 ' + $outDir)
    Write-Info ('  qxfly.conf                nginx 反代配置（server_name=' + $serverName + '，后端端口 ' + $port + '）')
    Write-Info '  qxfly-image-host.service  systemd 单元（WorkingDirectory=/opt/qxfly-image-host）'
    Write-Info '  DEPLOY-README.txt         服务端/客户端目录划分与部署步骤'
    if (-not $publicBase) { Write-Warn 'PUBLIC_BASE_URL 当前未设置：反代场景请在 config/local.env 里补上' }
}

# 把 ['-Zip','-SkipBuild','-Mode','prod'] 这种扁平参数解析成哈希表，
# 因为 PowerShell 的数组 splat 是「按位置传参」，只有哈希表 splat 才会做命名绑定。
function ConvertTo-NamedArgs {
    param([string[]]$Items)
    $table = @{}
    if (-not $Items) { return $table }
    $i = 0
    while ($i -lt $Items.Count) {
        $item = [string]$Items[$i]
        if ($item.StartsWith('-')) {
            $name = $item.TrimStart('-')
            if (($i + 1) -lt $Items.Count -and -not ([string]$Items[$i + 1]).StartsWith('-')) {
                $table[$name] = $Items[$i + 1]
                $i += 2
            } else {
                $table[$name] = $true
                $i += 1
            }
            continue
        }
        $i += 1
    }
    return $table
}

function Invoke-Child {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptName,
        [hashtable]$Named = @{},
        [string[]]$Positional = @()
    )
    $path = Join-Path $ScriptsDir $ScriptName
    if (-not (Test-Path $path)) { Write-Fail ('找不到脚本：' + $path); return }
    $pos = @($Positional | Where-Object { $null -ne $_ -and ([string]$_).Trim() -ne '' })
    if ($Named.Count -gt 0) {
        if ($pos.Count -gt 0) { & $path @Named @pos } else { & $path @Named }
    } else {
        if ($pos.Count -gt 0) { & $path @pos } else { & $path }
    }
}

function Show-Menu {
    Write-Banner 'qxfly-image-host 工具箱'
    Write-Host '   1) 启动（开发模式：后端 + Vite 前端）'
    Write-Host '   2) 启动（生产模式：仅后端，界面由 jar 提供）'
    Write-Host '   3) 查看运行状态'
    Write-Host '   4) 停止'
    Write-Host '   5) 重启'
    Write-Host '   6) 打包服务端包（向导：选环境 + 是否带运行时）'
    Write-Host '   7) 服务端包 · Linux 极简（不带运行时，约 90 MB）'
    Write-Host '   8) 服务端包 · Linux 带运行时（约 290 MB）'
    Write-Host '   9) 服务端包 · Windows 测试用（带 Windows JDK，约 460 MB）'
    Write-Host '  10) 打包完整包（Windows + Linux）'
    Write-Host '  11) 打包完整包（额外生成 zip）'
    Write-Host '  12) 打包桌面客户端（Tauri）'
    Write-Host '  13) 准备/校验便携运行时（JDK + Node）'
    Write-Host '  14) 数据库管家（状态/注册服务/启停/初始化库）'
    Write-Host '  15) 一键诊断（端口/环境/产物/配置，产出报告）'
    Write-Host '  16) 清理缓存与产物（先算体积再确认）'
    Write-Host '  17) 查看当前配置与访问地址'
    Write-Host '  18) 查看日志'
    Write-Host '  19) 生成部署模板（nginx / systemd）'
    Write-Host '  20) 脚本自检（编码与语法）'
    Write-Host '   0) 退出'
    Write-Host ''
}

function Invoke-Menu {
    while ($true) {
        Show-Menu
        $choice = (Read-Host '    请选择').Trim()
        Write-Host ''
        switch ($choice) {
            '1' { Invoke-Child 'start-services.ps1' }
            '2' { Invoke-Child 'start-services.ps1' -Named @{ Mode = 'prod'; Target = 'backend' } }
            '3' { Invoke-Child 'start-services.ps1' -Named @{ Action = 'status' } }
            '4' { Invoke-Child 'start-services.ps1' -Named @{ Action = 'stop' } }
            '5' { Invoke-Child 'start-services.ps1' -Named @{ Action = 'restart' } }
            '6' { Invoke-Child 'package-server.ps1' }
            '7' { Invoke-Child 'package-server.ps1' -Named @{ Env = 'linux'; Runtime = 'none' } }
            '8' { Invoke-Child 'package-server.ps1' -Named @{ Env = 'linux'; Runtime = 'linux' } }
            '9' { Invoke-Child 'package-server.ps1' -Named @{ Env = 'win'; Runtime = 'win' } }
            '10' { Invoke-Child 'package-release.ps1' }
            '11' { Invoke-Child 'package-release.ps1' -Named @{ Zip = $true } }
            '12' { Invoke-Child 'package-desktop.ps1' }
            '13' { Invoke-Child 'setup-portable-runtime.ps1' }
            '14' {
                $sub = (Read-Host '    子命令（status/install/start/stop/restart/autostart/path/init/console，回车=status）').Trim()
                if (-not $sub) { $sub = 'status' }
                Invoke-Child 'manage-mysql-service.ps1' -Positional @($sub)
            }
            '15' { Invoke-Diag }
            '16' { Invoke-Clean }
            '17' { Show-Config }
            '18' { Show-Logs }
            '19' { Invoke-Deploy }
            '20' { Invoke-Check }
            '0' { Write-Host '  再见'; return }
            default { Write-Warn '请输入 0-20 之间的编号' }
        }
        Write-Host ''
        $null = Read-Host '    回车返回菜单'
    }
}

# ================================================================== 入口
if (-not $Action) { Invoke-Menu; exit 0 }

switch ($Action.ToLower()) {
    'menu'    { Invoke-Menu }
    'help'    { Show-Menu }
    'start'   { $n = ConvertTo-NamedArgs $Rest; $n['Action'] = 'start'; Invoke-Child 'start-services.ps1' -Named $n }
    'stop'    { $n = ConvertTo-NamedArgs $Rest; $n['Action'] = 'stop'; Invoke-Child 'start-services.ps1' -Named $n }
    'restart' { $n = ConvertTo-NamedArgs $Rest; $n['Action'] = 'restart'; Invoke-Child 'start-services.ps1' -Named $n }
    'status'  { $n = ConvertTo-NamedArgs $Rest; $n['Action'] = 'status'; Invoke-Child 'start-services.ps1' -Named $n }
    'package' { Invoke-Child 'package-release.ps1' -Named (ConvertTo-NamedArgs $Rest) }
    'package-server' {
        # 一键打服务端包：不带参数进向导（选环境 + 是否带运行时）；
        # 也可直接给 -Env linux|win|both / -Runtime none|linux|win|both / -NoVerify / -SkipBuild
        $n = ConvertTo-NamedArgs $Rest
        Invoke-Child 'package-server.ps1' -Named $n
    }
    'desktop' { Invoke-Child 'package-desktop.ps1' -Named (ConvertTo-NamedArgs $Rest) }
    'runtime' { Invoke-Child 'setup-portable-runtime.ps1' -Named (ConvertTo-NamedArgs $Rest) }
    'db'      {
        $dbArgs = @($Rest | Where-Object { $_ -and -not ([string]$_).StartsWith('-') })
        if ($dbArgs.Count -eq 0) { $dbArgs = @('status') }
        Invoke-Child 'manage-mysql-service.ps1' -Named (ConvertTo-NamedArgs $Rest) -Positional $dbArgs
    }
    'check'   { if (-not (Invoke-Check)) { exit 1 } }
    'diag'    { Invoke-Diag }
    'clean'   { Invoke-Clean }
    'config'  { Show-Config }
    'logs'    { Show-Logs -Name ($Rest | Select-Object -First 1) }
    'deploy'  { Invoke-Deploy }
    default   { Write-Fail ('未知子命令：' + $Action); Write-Info '可用：menu help start stop restart status package package-server desktop runtime db check diag clean config logs deploy' }
}
