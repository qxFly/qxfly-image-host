#Requires -Version 5.1
<#
.SYNOPSIS
    把 qxfly-image-host 打成可分发的「一键包」：Windows 版与 Ubuntu 版。

.DESCRIPTION
    产物目录：
        dist\qxfly-image-host-win-x64\      自带 runtime\win-x64（JDK+Node 已解压），双击 start.cmd 即用
        dist\qxfly-image-host-linux-x64\    runtime\_cache 内含 Linux 版 JDK/Node 压缩包，
                                   Ubuntu 上首次执行 ./start.sh 会自动原生解压

    两个包都内置了生产版 jar（前端界面已打进 jar，-Mode prod 单进程运行）。

.EXAMPLE
    .\package.cmd                     # 构建并组装两个包
    .\package.cmd -SkipBuild          # 跳过编译，只用现有产物重新组装
    .\package.cmd -Zip                # 额外生成 zip 压缩包
#>
[CmdletBinding()]
param(
    # all    = Windows 完整包 + Linux 完整包（默认）
    # server = 只产出服务端专用包（Ubuntu，去掉前端源码/桌面壳/Windows 运行时，可瘦身一大半）
    [ValidateSet('all', 'server')][string]$Target = 'all',
    [switch]$SkipBuild,
    [switch]$Zip,
    # 打包完用包内 jar 起一次冒烟测试（临时端口 + H2），确认「包能跑起来」
    [switch]$Verify,
    # 服务端包包含哪套入口（= 要部署到哪个环境）：
    #   linux = 只放 Ubuntu 入口（start.sh / toolbox.sh / mysql.sh），体积最小
    #   win   = 只放 Windows 入口（start.cmd 等 + scripts\，用于在本机跑起来测试）
    #   both  = 两套都放（默认）
    [ValidateSet('linux', 'win', 'both')][string]$Env = 'both',
    # 服务端包附带哪些运行时（不显式指定时按 -Env 自动推导）：
    #   none  = 不带（目标机自备 JDK，体积最小）
    #   linux = 带 Linux 便携归档 runtime\_cache
    #   win   = 带 Windows 便携 JDK runtime\win-x64
    #   both  = 两者都带
    [ValidateSet('none', 'linux', 'win', 'both')][string]$Runtime = 'linux',
    # 等价于 -Runtime none（保留旧写法）
    [switch]$Slim,
    # 一键服务端包（package-server.ps1 用）
    [switch]$Server
)

$ErrorActionPreference = 'Stop'

# 一键服务端包：只在调用方没有显式指定时套用默认值（显式参数优先）
if ($Server) {
    $Target = 'server'
    if (-not $PSBoundParameters.ContainsKey('Verify')) { $Verify = $true }
}

# 没显式给 -Runtime 时按 -Env 推导：linux → 只带 Linux 归档；win → 只带 Windows JDK；both → 都带
if ($Target -eq 'server' -and -not $PSBoundParameters.ContainsKey('Runtime')) {
    switch ($Env) {
        'linux' { $Runtime = 'linux' }
        'win'   { $Runtime = 'win' }
        default { $Runtime = 'both' }
    }
}

$Root      = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $Root 'server'
$WebDir    = Join-Path $Root 'web'
$DistDir   = Join-Path $Root 'dist'
$CacheDir  = Join-Path $Root 'runtime\_cache'
$LogDir    = Join-Path $Root 'logs'
$ScriptsDir = $PSScriptRoot

# 公共库：提供 Test-Port / Wait-Port / Initialize-ProgressLog 等工具
# （本文件稍后自己定义的同名函数会覆盖它，行为保持不变）
. (Join-Path $PSScriptRoot 'common-library.ps1')

# 进度日志与错误 trap 都由公共库提供：每步都落盘，异常带行号报错，不再有静默退出
$null = Initialize-ProgressLog -Name 'package' -LogDir $LogDir

# 输出函数（Write-Step/Ok/Warn/Fail/Info）与 Invoke-Native 由公共库提供

# 停占用 jar / 复制 / 删除目录都由公共库提供：Stop-LockingJar、Copy-Tree、Remove-IfExists




function Get-DirSize {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return 0 }
    return (Get-ChildItem $Path -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
}

Write-Host ''
Write-Host '  ============================================================' -ForegroundColor DarkCyan
Write-Host '     qxfly-image-host 一键包构建' -ForegroundColor White
Write-Host '  ============================================================' -ForegroundColor DarkCyan

# ---------------------------------------------------------------- 编译
if (-not $SkipBuild) {
    Write-Step '构建前端 ...'
    Push-Location $WebDir
    try {
        Invoke-Native { npm install --no-audit --no-fund } | Out-Null
        Invoke-Native { npm run build }
        if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    } finally { Pop-Location }
    Write-Ok '前端构建完成'
    Write-Progress-Log 'WEB-BUILD-OK'

    Write-Step '打包后端（含前端界面，-Pwith-web）...'
    Stop-LockingJar -JarPaths @(
        (Join-Path $ServerDir 'target\qxfly-image-host.jar'),
        (Join-Path $DistDir 'qxfly-image-host-win-x64\server\target\qxfly-image-host.jar'),
        (Join-Path $DistDir 'qxfly-image-host-linux-x64\server\target\qxfly-image-host.jar')
    )
    $mvn = Join-Path $ServerDir 'mvnw.cmd'
    Push-Location $ServerDir
    try {
        Invoke-Native { & $mvn -B '-DskipTests' '-Pwith-web' clean package }
        if ($LASTEXITCODE -ne 0) {
            Write-Fail '后端构建失败'
            Write-Info '常见原因：后端仍在运行导致 clean 删不掉 target\qxfly-image-host.jar；'
            Write-Info '       或端口 8080 被占用。关闭后端后重试即可（脚本已尝试自动停掉占用进程）。'
            throw '后端构建失败'
        }
    } finally { Pop-Location }
    Write-Ok '后端构建完成'
    Write-Progress-Log 'BACKEND-BUILD-OK'
}

$jar = Join-Path $ServerDir 'target\qxfly-image-host.jar'
if (-not (Test-Path $jar)) { Write-Fail ('缺少 ' + $jar + '，请先执行构建（去掉 -SkipBuild）'); exit 1 }
$distWeb = Join-Path $WebDir 'dist'
if (-not (Test-Path (Join-Path $distWeb 'index.html'))) { Write-Fail '缺少 web\dist，请先构建前端'; exit 1 }

# ---------------------------------------------------------------- 公共内容
function New-PackageBase {
    param([string]$Target)

    Write-Progress-Log ('  fn begin ' + $Target)
    Remove-IfExists $Target
    Write-Progress-Log '  fn after Remove-IfExists'
    New-Item -ItemType Directory -Force -Path $Target | Out-Null

    # 后端
    $serverOut = Join-Path $Target 'server'
    New-Item -ItemType Directory -Force -Path (Join-Path $serverOut 'target') | Out-Null
    foreach ($item in @('pom.xml', 'mvnw', 'mvnw.cmd', '.mvn', 'src')) {
        $src = Join-Path $ServerDir $item
        if (Test-Path $src) { Copy-Tree -From $src -To (Join-Path $serverOut $item) }
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $serverOut 'target\qxfly-image-host.jar') -Force

    # 前端
    $webOut = Join-Path $Target 'web'
    foreach ($item in @('package.json', 'package-lock.json', 'vite.config.ts', 'tsconfig.json', 'index.html', 'env.d.ts', 'src', 'dist')) {
        $src = Join-Path $WebDir $item
        if (Test-Path $src) { Copy-Tree -From $src -To (Join-Path $webOut $item) }
    }
    # src-tauri 逐项拷贝：必须排除 target（Rust 构建产物可达 1GB 以上）
    foreach ($item in @('Cargo.toml', 'build.rs', 'tauri.conf.json', 'capabilities', 'icons', 'src')) {
        $src = Join-Path $WebDir ('src-tauri\' + $item)
        if (Test-Path $src) { Copy-Tree -From $src -To (Join-Path $webOut ('src-tauri\' + $item)) }
    }

    # 脚本与配置
    Copy-Tree -From (Join-Path $Root 'scripts') -To (Join-Path $Target 'scripts')
    New-Item -ItemType Directory -Force -Path (Join-Path $Target 'config') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $Target 'logs') | Out-Null
    foreach ($item in @('docker-compose.yml', 'README.md')) {
        $src = Join-Path $Root $item
        if (Test-Path $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $Target $item) -Force }
    }
    foreach ($item in @('docs')) {
        $src = Join-Path $Root $item
        if (Test-Path $src) { Copy-Tree -From $src -To (Join-Path $Target $item) }
    }
}

# ================================================================ 完整包（Windows + Linux）
if ($Target -eq 'all') {
# ---------------------------------------------------------------- Windows 包
$winTarget = Join-Path $DistDir 'qxfly-image-host-win-x64'
Write-Progress-Log 'WIN-ASSEMBLY-BEGIN'
Write-Step '组装 Windows 包 ...'
New-PackageBase -Target $winTarget
Copy-Tree -From (Join-Path $Root 'runtime\win-x64') -To (Join-Path $winTarget 'runtime\win-x64')
    # start-services.ps1 已经随 scripts\ 一起复制过去（实现在 scripts\ 下），这里只放 Windows 的根入口
    foreach ($item in @('start.cmd', 'mysql.cmd', 'mysql-service.cmd')) {
    Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $winTarget $item) -Force
}
Write-Progress-Log 'WIN-ASSEMBLY-OK'
Write-Ok ('Windows 包完成：' + [math]::Round((Get-DirSize $winTarget) / 1MB, 1) + ' MB')

# ---------------------------------------------------------------- Linux 包
$linuxTarget = Join-Path $DistDir 'qxfly-image-host-linux-x64'
Write-Progress-Log 'LINUX-ASSEMBLY-BEGIN'
Write-Step '组装 Linux 包 ...'
New-PackageBase -Target $linuxTarget
New-Item -ItemType Directory -Force -Path (Join-Path $linuxTarget 'runtime\_cache') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $linuxTarget 'runtime\linux-x64') | Out-Null
foreach ($archive in @('jdk-linux-x64.tar.gz', 'node-linux-x64.tar.xz')) {
    $src = Join-Path $CacheDir $archive
    if (Test-Path $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $linuxTarget ('runtime\_cache\' + $archive)) -Force }
    else { Write-Warn ('缺少 Linux 运行时压缩包：' + $archive + '（先在 Windows 上执行 scripts\setup-portable-runtime.ps1）') }
}
foreach ($item in @('start.sh', 'mysql.sh', 'toolbox.sh')) {
    Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $linuxTarget $item) -Force
}
Write-Progress-Log 'LINUX-ASSEMBLY-OK'
Write-Ok ('Linux 包完成：' + [math]::Round((Get-DirSize $linuxTarget) / 1MB, 1) + ' MB')
}

# ---------------------------------------------------------------- 打包
if ($Zip) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($name in @('qxfly-image-host-win-x64', 'qxfly-image-host-linux-x64')) {
        $folder = Join-Path $DistDir $name
        $zipPath = Join-Path $DistDir ($name + '.zip')
        if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
        Write-Step ('压缩 ' + $name + ' ...')
        [System.IO.Compression.ZipFile]::CreateFromDirectory($folder, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
        Write-Ok ($name + '.zip  ' + [math]::Round((Get-Item $zipPath).Length / 1MB, 1) + ' MB')
    }
}

# ---------------------------------------------------------------- 部署模板 / BUILD-INFO / 服务端专用包 / 冒烟测试
$deployDir = Join-Path $DistDir 'deploy'
if (-not (Test-Path (Join-Path $deployDir 'qxfly.conf'))) {
    Write-Step '生成部署模板（nginx / systemd）...'
    & (Join-Path $ScriptsDir 'toolbox-menu.ps1') deploy
}

# git 信息通过「输出重定向到文件」获取，不用管道捕获外部程序输出（见文件头说明）
function Get-GitValue {
    param([string[]]$GitArgs)
    $out = Join-Path ([System.IO.Path]::GetTempPath()) ('qxfly-git-' + [guid]::NewGuid().ToString('N') + '.txt')
    $value = ''
    try {
        Start-Process -FilePath 'git.exe' -ArgumentList $GitArgs -Wait -PassThru -WindowStyle Hidden -RedirectStandardOutput ('"' + $out + '"') | Out-Null
        if (Test-Path $out) { $value = (Get-Content -LiteralPath $out -Raw).Trim() }
    } catch { }
    Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue
    return $value
}

function New-BuildInfo {
    param([string]$PackageDir, [string]$Kind)
    if (-not (Test-Path $PackageDir)) { return }
    $jarPath = Join-Path $PackageDir 'server\target\qxfly-image-host.jar'
    $appVersion = '0.1.0'
    try {
        [xml]$pom = Get-Content -LiteralPath (Join-Path $ServerDir 'pom.xml') -Raw
        if ($pom.project.version) { $appVersion = $pom.project.version }
    } catch { }
    $jarLine = '缺失'
    if (Test-Path $jarPath) {
        $size = [math]::Round((Get-Item $jarPath).Length / 1MB, 2)
        $hash = (Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.Substring(0, 16).ToLower()
        $jarLine = ($size.ToString() + ' MB, sha256 ' + $hash + '...')
    }
    $commit = Get-GitValue @('rev-parse', '--short', 'HEAD')
    $branch = Get-GitValue @('rev-parse', '--abbrev-ref', 'HEAD')
    $info = @(
        'qxfly-image-host 构建信息',
        '-------------------------------',
        ('包类型   : ' + $Kind),
        ('构建时间 : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')),
        ('应用版本 : ' + $appVersion),
        ('Git 提交 : ' + $(if ($commit) { $commit + ' (' + $branch + ')' } else { '无 git 信息' })),
        ('jar      : ' + $jarLine),
        'Node     : 生产模式不需要（界面已打进 jar）',
        ''
    )
    $info | Set-Content -LiteralPath (Join-Path $PackageDir 'BUILD-INFO.txt') -Encoding UTF8
}

# 用包内 jar 起一次（临时端口 + H2），确认包真的能跑起来
function Test-PackagedJar {
    param([string]$JarPath)
    if (-not (Test-Path $JarPath)) { Write-Warn ('跳过冒烟测试：找不到 ' + $JarPath); return $false }
    $java = Join-Path $Root 'runtime\win-x64\jdk\bin\java.exe'
    if (-not (Test-Path $java)) { $java = 'java' }
    $port = 18080
    while ((Test-Port -Port $port) -and $port -lt 18120) { $port++ }
    Write-Step ('冒烟测试：用包内 jar 启动一次（端口 ' + $port + '，H2 内存库）...')
    $outLog = Join-Path $LogDir 'package-smoke.log'
    $errLog = Join-Path $LogDir 'package-smoke.err.log'
    # -jar 后面的路径含空格时必须加引号，否则 java 拿到被截断的路径
    $proc = Start-Process -FilePath $java -ArgumentList @('-Dspring.profiles.active=h2', ('-Dserver.port=' + $port), '-Dfile.encoding=UTF-8', '-jar', ('"' + $JarPath + '"')) -PassThru -WindowStyle Hidden -RedirectStandardOutput $outLog -RedirectStandardError $errLog
    $ok = $false
    try {
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Milliseconds 1000
            if ($proc.HasExited) { break }
            try {
                $resp = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $port + '/actuator/health') -UseBasicParsing -TimeoutSec 3
                if ($resp.StatusCode -eq 200) { $ok = $true; break }
            } catch { }
        }
    } finally {
        try { if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force } } catch { }
        Start-Sleep -Milliseconds 500
    }
    if ($ok) { Write-Ok '冒烟测试通过：/actuator/health 返回 200' }
    else { Write-Warn ('冒烟测试未通过，请看 ' + $outLog + ' / ' + $errLog) }
    return $ok
}

# 1) 完整包：补部署模板 + BUILD-INFO
if ($Target -eq 'all') {
    foreach ($pkg in @(@($winTarget, 'Windows 完整包'), @($linuxTarget, 'Linux 完整包'))) {
        if (Test-Path $pkg[0]) {
            if (Test-Path $deployDir) { Copy-Tree -From $deployDir -To (Join-Path $pkg[0] 'deploy') }
            New-BuildInfo -PackageDir $pkg[0] -Kind $pkg[1]
        }
    }
}

# 2) 服务端包：扁平布局，上传到服务器即可直接启动
#    - jar 直接放包根目录（不再是 server\target\ 两层嵌套）
#    - Ubuntu 与 Windows 两套一键入口都带上：Windows 上双击 start.cmd 就能测
#    - 运行时按 -Runtime none|linux|win|both 决定（默认 linux：上传体积最小）
if ($Target -eq 'server') {
    if ($Slim) { $Runtime = 'none' }
    $incLinux = ($Env -eq 'linux' -or $Env -eq 'both')
    $incWin   = ($Env -eq 'win' -or $Env -eq 'both')
    $runLinux = (($Runtime -eq 'linux' -or $Runtime -eq 'both') -and $incLinux)
    $runWin   = (($Runtime -eq 'win' -or $Runtime -eq 'both') -and $incWin)
    if ($Runtime -ne 'none' -and -not $runLinux -and -not $runWin) {
        Write-Warn ('运行时 ' + $Runtime + ' 与所选环境 ' + $Env + ' 不匹配，本包不附带任何运行时')
    }
    Write-Progress-Log 'SERVER-PKG-BEGIN'
    Write-Step ('组装服务端包（环境=' + $Env + '，运行时=' + $Runtime + '）...')
    $serverTarget = Join-Path $DistDir 'qxfly-image-host-server'
    Remove-IfExists $serverTarget
    New-Item -ItemType Directory -Force -Path $serverTarget | Out-Null
    foreach ($d in @('config', 'deploy', 'logs', 'uploads', 'thumbs', 'runtime')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $serverTarget $d) | Out-Null
    }
    # 应用本体：jar 在包根
    Copy-Item -LiteralPath $jar -Destination (Join-Path $serverTarget 'qxfly-image-host.jar') -Force
    # Ubuntu 入口（-Env linux|both 才放）
    if ($incLinux) {
        foreach ($item in @('start.sh', 'mysql.sh', 'toolbox.sh')) {
            if (Test-Path (Join-Path $Root $item)) { Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $serverTarget $item) -Force }
        }
    }
    # Windows 一键入口 + 实现脚本（-Env win|both 才放）
    if ($incWin) {
        foreach ($item in @('start.cmd', 'toolbox.cmd', 'mysql.cmd', 'mysql-service.cmd')) {
            if (Test-Path (Join-Path $Root $item)) { Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $serverTarget $item) -Force }
        }
        Copy-Tree -From (Join-Path $Root 'scripts') -To (Join-Path $serverTarget 'scripts')
    }
    # 运行时：按 -Runtime 与 -Env 的交集决定附带哪些
    if ($Runtime -eq 'none') {
        Write-Warn '未附带运行时：目标机需自备 JDK 17+（Linux: apt install openjdk-17-jre-headless；Windows: 安装 JDK 或加入 PATH）'
    } else {
        if ($runLinux) {
            # 只带 Linux 需要的两个归档：runtime\_cache 里同时放着 Windows 的 zip，不该跟着走
            $cacheDest = Join-Path $serverTarget 'runtime\_cache'
            New-Item -ItemType Directory -Force -Path $cacheDest | Out-Null
            foreach ($archive in @('jdk-linux-x64.tar.gz', 'node-linux-x64.tar.xz')) {
                $archiveSrc = Join-Path $CacheDir $archive
                if (Test-Path $archiveSrc) { Copy-Item -LiteralPath $archiveSrc -Destination (Join-Path $cacheDest $archive) -Force }
                else { Write-Warn ('缺少 Linux 运行时归档：' + $archive + '（可先执行 toolbox.cmd runtime）') }
            }
        }
        if ($runWin -and (Test-Path (Join-Path $Root 'runtime\win-x64'))) {
            # 用分批 + 校验 + 续拷：这台机器复制 380 MB 小文件树时进程可能被打断，
            # 一旦不完整就直接抛错，避免留下一个看起来正常的残包
            Copy-TreeVerified -From (Join-Path $Root 'runtime\win-x64') -To (Join-Path $serverTarget 'runtime\win-x64')
        }
    }
    # 部署模板：只带两个模板文件，说明统一放包根 DEPLOY-README.txt
    if (Test-Path $deployDir) {
        $confSrc = Join-Path $deployDir 'qxfly.conf'
        if (Test-Path $confSrc) { Copy-Item -LiteralPath $confSrc -Destination (Join-Path $serverTarget 'deploy\nginx.conf.example') -Force }
        $unitSrc = Join-Path $deployDir 'qxfly-image-host.service'
        if (Test-Path $unitSrc) { Copy-Item -LiteralPath $unitSrc -Destination (Join-Path $serverTarget 'deploy\qxfly-image-host.service') -Force }
    }
    $envTemplate = @(
        '# qxfly-image-host server config template: rename to local.env and edit',
        '# Backend port: the launchers read BACKEND_PORT, and SERVER_PORT is also accepted.',
        '# Both mean the same thing - the launcher exports SERVER_PORT to the app either way.',
        'BACKEND_PORT=8080',
        'SERVER_ADDRESS=127.0.0.1',
        '# set to the public origin when behind a reverse proxy, e.g. https://img.example.com',
        '# direct access: http://<server-ip>:8080',
        'PUBLIC_BASE_URL=',
        'LOCAL_STORAGE_DIR=uploads',
        'THUMB_CACHE_DIR=thumbs',
        'MYSQL_HOST=127.0.0.1',
        'MYSQL_PORT=3306',
        'MYSQL_DATABASE=qxfly-image-host',
        'MYSQL_USER=root',
        'MYSQL_PASSWORD=',
        'JAVA_OPTS=-Xms256m -Xmx1g -XX:+UseG1GC'
    )
    # 写成不带 BOM 的 UTF-8：.env 会被 bash/sed 逐行解析，BOM 会破坏第一行的变量名
    [System.IO.File]::WriteAllText((Join-Path $serverTarget 'config\local.env.example'), ($envTemplate -join [char]10), (New-Object System.Text.UTF8Encoding($false)))
    # 包根部署说明：把「这个包里都是什么 / 上传后怎么启动」放在最显眼的位置
    $readmeLines = @(
        'qxfly-image-host 服务端包 —— 上传到服务器后直接启动',
        '=====================================================',
        '',
        '一、这个包里有什么',
        '  qxfly-image-host.jar              应用本体（前端界面已打进 jar，单进程同时提供接口和页面）',
        $(if ($incLinux) { '  start.sh / toolbox.sh / mysql.sh    Ubuntu / Linux 入口与工具' } else { '' }),
        $(if ($incWin) { '  start.cmd / toolbox.cmd / scripts/ Windows 一键入口（双击 start.cmd 即可测试）' } else { '' }),
        '  BUILD-INFO.txt                    构建信息（版本 / 构建时间 / jar 哈希）',
        '  config/local.env.example          配置模板 → 复制成 local.env 再改',
        '  deploy/nginx.conf.example         nginx 反向代理示例',
        '  deploy/qxfly-image-host.service   systemd 单元（生产推荐）',
        $(if ($Runtime -eq 'none') { '  runtime/                          （本包未附带运行时，目标机需自备 JDK 17+）' } else { '  runtime/                          便携运行时（Linux 首次启动自动解压；Windows 已解压可直接用）' }),
        '  uploads/                          图片实体（运行后生成，必须备份）',
        '  thumbs/                           缩略图缓存（可随时删）',
        '  logs/                             日志',
        '',
        $(if ($incLinux) { '二、上传后怎么启动（三步）' } else { '二、本机怎么启动（Windows 测试包，没有 Linux 入口）' }),
        $(if ($incLinux) { '  1) 把整个 qxfly-image-host-server 目录上传到服务器，例如 /opt/qxfly-image-host' } else { '  1) 这个包只带 Windows 入口：直接在本机运行 start.cmd 测试' }),
        '  2) cp config/local.env.example config/local.env    # 改端口 / 数据库 / PUBLIC_BASE_URL',
        $(if ($incLinux) { '  3) ./start.sh -Mode prod' } else { '  3) start.cmd -Mode prod' }),
        $(if ($incLinux) { '     看到「后端健康检查通过」即成功；浏览器访问 http://<服务器IP>:8080' } else { '     看到「后端健康检查通过」即成功；浏览器访问 http://127.0.0.1:8080' }),
        $(if ($incLinux) { '     停止：./start.sh -Action stop' } else { '     停止：start.cmd -Action stop' }),
        '',
        '三、生产建议（systemd 开机自启 + 崩溃自拉起）',
        '  sudo cp deploy/qxfly-image-host.service /etc/systemd/system/',
        '  sudo systemctl daemon-reload && sudo systemctl enable --now qxfly-image-host',
        '  journalctl -u qxfly-image-host -f',
        '  注意：unit 里的 WorkingDirectory 必须是这个包的根目录（uploads/thumbs/logs 都相对它）',
        '',
        '四、反向代理（nginx）',
        '  sudo cp deploy/nginx.conf.example /etc/nginx/conf.d/qxfly.conf',
        '  改 server_name 后：sudo nginx -t && sudo systemctl reload nginx',
        '  client_max_body_size 必须 >= 应用里设置的单张上传上限',
        '  反代时务必在 config/local.env 设置 PUBLIC_BASE_URL，否则生成的链接会指向错误地址',
        '',
        $(if ($incWin) { '五、在 Windows 上先测一遍（推荐，2 分钟）' } else { '五、Windows 测试' }),
        $(if ($incWin) { '  双击 start.cmd 即可（自动按生产模式只跑后端，并打开浏览器）' } else { '  本包不含 Windows 入口：请在 Windows 上另打一个包：package-server.cmd -Env win' }),
        $(if ($incWin) { '  不想碰本机 MySQL、用内存库快速验证：' } else { '' }),
        $(if ($incWin) { '    start.cmd -Mode prod -Database h2 -BackendPort 18090 -NoBrowser' } else { '' }),
        $(if ($incWin) { '  停止：start.cmd -Action stop -BackendPort 18090' } else { '' }),
        $(if ($runWin) { '  本包已附带 Windows 便携 JDK（runtime/win-x64），离线可测。' } elseif ($incWin) { '  本包未带 Windows 运行时：首次启动会自动下载便携 JDK（约 180 MB，需联网）。' } else { '' }),
        '',
        '七、排查',
        '  ./toolbox.sh diag   一键诊断      ./toolbox.sh logs   看日志',
        '  docs/DEPLOY-ubuntu.md（仓库内）   目录划分、备份、升级、排查'
    )
    # 带 BOM 写：Windows 的 PowerShell 5.1 / 记事本按 ANSI 读无 BOM 的 UTF-8 会乱码（与 BUILD-INFO.txt 一致）
    [System.IO.File]::WriteAllText((Join-Path $serverTarget 'DEPLOY-README.txt'), ($readmeLines -join [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($true)))
    $kind = '服务端包（扁平布局，环境=' + $Env + '，运行时=' + $Runtime + '）'
    New-BuildInfo -PackageDir $serverTarget -Kind $kind
    $mb = [math]::Round((Get-DirSize $serverTarget) / 1MB, 1)
    Write-Ok ('服务端包完成：' + $mb + ' MB  ->  ' + $serverTarget)
    Write-Info '包根就是部署目录：qxfly-image-host.jar + start.sh + config/ + deploy/ + runtime/ + uploads/'
    Write-Info '上传后：cp config/local.env.example config/local.env 改好 → ./start.sh -Mode prod'
    Write-Progress-Log 'SERVER-PKG-OK'
}

# 3) 冒烟测试（-Verify）
$smokeOk = $true
if ($Verify) {
    if ($Target -eq 'server') { $smokeJar = Join-Path $DistDir 'qxfly-image-host-server\qxfly-image-host.jar' }
    else { $smokeJar = Join-Path $DistDir 'qxfly-image-host-linux-x64\server\target\qxfly-image-host.jar' }
    $smokeOk = Test-PackagedJar -JarPath $smokeJar
}
Write-Progress-Log 'DONE-ALL'
Write-Host ''
Write-Ok ('产物目录：' + $DistDir)
if (Test-Path (Join-Path $DistDir 'qxfly-image-host-win-x64')) { Write-Info 'Windows：把 qxfly-image-host-win-x64 拷到目标机，双击 start.cmd' }
if (Test-Path (Join-Path $DistDir 'qxfly-image-host-linux-x64')) { Write-Info 'Ubuntu ：把 qxfly-image-host-linux-x64 拷到目标机，./start.sh（首次会自动解压运行时）' }
if (Test-Path (Join-Path $DistDir 'qxfly-image-host-server')) { Write-Info '服务端：把 qxfly-image-host-server 整个上传到 Ubuntu，./start.sh -Mode prod（说明见包根 DEPLOY-README.txt）' }
if (-not $smokeOk) { Write-Warn '冒烟测试未通过，这个包可能不可用' }
Write-Info ('进度日志：' + (Get-ProgressLogPath))
Write-Host ''
