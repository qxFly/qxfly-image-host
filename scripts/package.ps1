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
    [switch]$SkipBuild,
    [switch]$Zip
)

$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent $PSScriptRoot
$ServerDir = Join-Path $Root 'server'
$WebDir    = Join-Path $Root 'web'
$DistDir   = Join-Path $Root 'dist'
$CacheDir  = Join-Path $Root 'runtime\_cache'

function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

function Invoke-Native {
    param([Parameter(Mandatory = $true)][scriptblock]$Script)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return & $Script } finally { $ErrorActionPreference = $previous }
}

# 用 robocopy 而不是 Copy-Item：几百 MB 的运行时快得多，
# 而且在受限环境（安全软件 / 沙箱）下对 ucrtbase.dll 这类文件更稳。
function Copy-Tree {
    param([string]$From, [string]$To)
    if (-not (Test-Path $From)) { Write-Warn ('跳过不存在的路径：' + $From); return }
    $parent = Split-Path -Parent $To
    if ($parent -and -not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    if (Test-Path $To) { & cmd.exe /c ('rmdir /s /q "' + $To + '"') | Out-Null }

    $isDir = (Get-Item -LiteralPath $From).PSIsContainer
    if (-not $isDir) {
        Copy-Item -LiteralPath $From -Destination $To -Force
        return
    }
    Invoke-Native { & robocopy.exe $From $To /E /NFL /NDL /NJH /NJS /NP /R:2 /W:2 } | Out-Null
    if ($LASTEXITCODE -ge 8) { throw ('robocopy 复制失败（退出码 ' + $LASTEXITCODE + '）：' + $From) }
}

function Remove-IfExists {
    param([string]$Path)
    if (Test-Path $Path) { & cmd.exe /c ('rmdir /s /q "' + $Path + '"') | Out-Null }
}

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

    Write-Step '打包后端（含前端界面，-Pwith-web）...'
    $mvn = Join-Path $ServerDir 'mvnw.cmd'
    Push-Location $ServerDir
    try {
        Invoke-Native { & $mvn -B '-DskipTests' '-Pwith-web' clean package }
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败' }
    } finally { Pop-Location }
    Write-Ok '后端构建完成'
}

$jar = Join-Path $ServerDir 'target\qxfly-image-host.jar'
if (-not (Test-Path $jar)) { Write-Fail ('缺少 ' + $jar + '，请先执行构建（去掉 -SkipBuild）'); exit 1 }
$distWeb = Join-Path $WebDir 'dist'
if (-not (Test-Path (Join-Path $distWeb 'index.html'))) { Write-Fail '缺少 web\dist，请先构建前端'; exit 1 }

# ---------------------------------------------------------------- 公共内容
function New-PackageBase {
    param([string]$Target)

    Remove-IfExists $Target
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

# ---------------------------------------------------------------- Windows 包
$winTarget = Join-Path $DistDir 'qxfly-image-host-win-x64'
Write-Step '组装 Windows 包 ...'
New-PackageBase -Target $winTarget
Copy-Tree -From (Join-Path $Root 'runtime\win-x64') -To (Join-Path $winTarget 'runtime\win-x64')
foreach ($item in @('start.ps1', 'start.cmd', 'mysql-service.cmd', 'mysql.cmd')) {
    Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $winTarget $item) -Force
}
Write-Ok ('Windows 包完成：' + [math]::Round((Get-DirSize $winTarget) / 1MB, 1) + ' MB')

# ---------------------------------------------------------------- Linux 包
$linuxTarget = Join-Path $DistDir 'qxfly-image-host-linux-x64'
Write-Step '组装 Linux 包 ...'
New-PackageBase -Target $linuxTarget
New-Item -ItemType Directory -Force -Path (Join-Path $linuxTarget 'runtime\_cache') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $linuxTarget 'runtime\linux-x64') | Out-Null
foreach ($archive in @('jdk-linux-x64.tar.gz', 'node-linux-x64.tar.xz')) {
    $src = Join-Path $CacheDir $archive
    if (Test-Path $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $linuxTarget ('runtime\_cache\' + $archive)) -Force }
    else { Write-Warn ('缺少 Linux 运行时压缩包：' + $archive + '（先在 Windows 上执行 scripts\setup-runtime.ps1）') }
}
foreach ($item in @('start.sh', 'mysql.sh')) {
    Copy-Item -LiteralPath (Join-Path $Root $item) -Destination (Join-Path $linuxTarget $item) -Force
}
Write-Ok ('Linux 包完成：' + [math]::Round((Get-DirSize $linuxTarget) / 1MB, 1) + ' MB')

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

Write-Host ''
Write-Ok ('产物目录：' + $DistDir)
Write-Info 'Windows：把 qxfly-image-host-win-x64 拷到目标机，双击 start.cmd'
Write-Info 'Ubuntu ：把 qxfly-image-host-linux-x64 拷到目标机，./start.sh（首次会自动解压运行时）'
Write-Host ''
