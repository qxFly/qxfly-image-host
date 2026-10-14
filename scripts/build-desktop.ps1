#Requires -Version 5.1
<#
.SYNOPSIS
    把 qxfly-image-host 打包成 Tauri 2 桌面客户端（Windows 免安装 exe + 安装包）。

.DESCRIPTION
    产物（构建目录）：
        web\src-tauri\target\release\qxfly-image-host.exe          免安装，双击即用
        web\src-tauri\target\release\bundle\nsis\*-setup.exe     NSIS 安装包
        web\src-tauri\target\release\bundle\msi\*.msi            MSI 安装包
    脚本会把生成的文件复制到 dist\desktop\ 方便分发。

    前置条件：
        1. Rust（rustup，默认 MSVC 工具链）           https://rustup.rs
        2. Visual Studio 生成工具（C++ 桌面开发）     或者完整的 Visual Studio
        3. WebView2 Runtime（Win11 自带；Win10 多数已自带，没有就装 Evergreen Runtime）
        4. Node 18+（web\node_modules，脚本会按需 npm install）
    Tauri 首次「做安装包」时会自动下载 NSIS / WiX，需要能访问外网；
    下载不通就用 -NoBundle 只出免安装 exe（功能完全一样）。

.EXAMPLE
    .\scripts\build-desktop.ps1
        完整打包（exe + NSIS/MSI 安装包）

.EXAMPLE
    .\scripts\build-desktop.ps1 -NoBundle
        只出免安装 exe，不下载打包器（国内网络最稳）

.EXAMPLE
    .\scripts\build-desktop.ps1 -ApiBase https://img.example.com/api
        让客户端默认连这个后端（也可以打包后用环境变量 QXFLY_IMAGE_HOST_API_BASE 覆盖）

.EXAMPLE
    .\scripts\build-desktop.ps1 -BackendPort 8081
        后端在本机别的端口时用（等价于打包时写死 http://127.0.0.1:8081/api）
#>
[CmdletBinding()]
param(
    # 只生成免安装 exe，不做安装包（跳过 NSIS / WiX 下载，国内最快）
    [switch]$NoBundle,
    # 打包时写死的后端地址，例如 https://img.example.com/api
    [string]$ApiBase = '',
    # 打包时写死的后端端口（默认 8080，等价于 http://127.0.0.1:<端口>/api）
    [string]$BackendPort = '',
    # 跳过 npm install
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

$Root    = Split-Path -Parent $PSScriptRoot
$WebDir  = Join-Path $Root 'web'
$TauriDir = Join-Path $WebDir 'src-tauri'
$OutDir  = Join-Path $Root 'dist\desktop'

function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

Write-Host ''
Write-Host '  ============================================================' -ForegroundColor DarkCyan
Write-Host '     qxfly-image-host  ·  桌面客户端打包（Tauri 2）' -ForegroundColor White
Write-Host '  ============================================================' -ForegroundColor DarkCyan

if (-not (Test-Path (Join-Path $WebDir 'package.json'))) {
    Write-Fail ('当前目录不像是 qxfly-image-host 项目根目录：' + $Root)
    exit 1
}

# ---------------------------------------------------------------- 前置检查
Write-Step '检查构建工具链 ...'
$cargo = Get-Command cargo -ErrorAction SilentlyContinue
if (-not $cargo) {
    Write-Fail '没找到 cargo（Rust 工具链）'
    Write-Info '安装：https://rustup.rs  （装完重开一个终端）'
    Write-Info 'Windows 还需要 Visual Studio 生成工具里的「使用 C++ 的桌面开发」'
    exit 1
}
$rustc = & rustc --version 2>&1
Write-Ok ('Rust: ' + $rustc)
Write-Info ('cargo: ' + $cargo.Source)

if ($env:OS -eq 'Windows_NT') {
    $webview = Get-ItemProperty 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}' -ErrorAction SilentlyContinue
    if ($webview) {
        Write-Ok ('WebView2 Runtime: ' + $webview.pv)
    } else {
        Write-Warn '没检测到 WebView2 Runtime：Win10 需要单独安装（Win11 自带）'
        Write-Info '下载：https://developer.microsoft.com/microsoft-edge/webview2/'
    }
}

$npm = if ($env:OS -eq 'Windows_NT') { 'npm.cmd' } else { 'npm' }
if (-not (Get-Command $npm -ErrorAction SilentlyContinue)) {
    Write-Fail ('没找到 ' + $npm + '，请先安装 Node.js 18+')
    exit 1
}

# ---------------------------------------------------------------- 依赖与参数
if (-not $SkipInstall -and -not (Test-Path (Join-Path $WebDir 'node_modules'))) {
    Write-Step '安装前端依赖（首次需要几分钟）...'
    Push-Location $WebDir
    try { & $npm install --no-audit --no-fund } finally { Pop-Location }
}

if ($BackendPort) {
    $env:VITE_BACKEND_PORT = $BackendPort
    Write-Ok ('客户端默认后端端口：' + $BackendPort)
}
if ($ApiBase) {
    $env:VITE_API_BASE = $ApiBase
    Write-Ok ('客户端默认接口地址：' + $ApiBase)
}
if (-not $env:VITE_API_BASE -and -not $env:VITE_BACKEND_PORT) {
    Write-Info '客户端默认连本机 http://127.0.0.1:8080/api'
    Write-Info '（打包后也可以用环境变量 QXFLY_IMAGE_HOST_API_BASE 指向别的后端，不用重打包）'
}

# ---------------------------------------------------------------- 编译
$tauriArgs = @('run', 'tauri:build')
if ($NoBundle) { $tauriArgs += @('--', '--no-bundle') }

Write-Step ('开始打包（Rust release 构建，首次 10~20 分钟）' + $(if ($NoBundle) { '，跳过安装包' } else { '' }) + ' ...')
$started = Get-Date
Push-Location $WebDir
try {
    & $npm @tauriArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
$spent = [int]((Get-Date) - $started).TotalSeconds
Write-Info ('耗时 ' + $spent + ' 秒')

# ---------------------------------------------------------------- 收集产物
$exe = Join-Path $TauriDir 'target\release\qxfly-image-host.exe'
$bundleDir = Join-Path $TauriDir 'target\release\bundle'

if ($code -ne 0 -and -not (Test-Path $exe)) {
    Write-Fail '打包失败，没有生成 exe。常见原因：'
    Write-Info '1) 没装 Visual Studio 生成工具的 C++ 桌面开发组件'
    Write-Info '2) Rust 工具链不是 msvc（rustup default stable-x86_64-pc-windows-msvc）'
    Write-Info '3) 安装包下载超时 —— 加 -NoBundle 只出 exe 再试'
    exit $code
}
if ($code -ne 0 -and (Test-Path $exe)) {
    Write-Warn 'exe 已生成，但安装包这一步失败了（多数是 NSIS / WiX 下载不通）'
    Write-Info '免安装 exe 功能完全一样，直接分发即可；想再要安装包可重跑一次或挂代理'
}

# 先清空产物目录，避免留下上一次构建的旧文件（例如改过安装包语言之后）
if (Test-Path $OutDir) { Remove-Item -Recurse -Force $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$copied = @()
if (Test-Path $exe) {
    Copy-Item -LiteralPath $exe -Destination (Join-Path $OutDir 'qxfly-image-host.exe') -Force
    $copied += (Join-Path $OutDir 'qxfly-image-host.exe')
}
if (Test-Path $bundleDir) {
    foreach ($file in (Get-ChildItem $bundleDir -Recurse -File -Include '*.exe', '*.msi' -ErrorAction SilentlyContinue)) {
        $target = Join-Path $OutDir $file.Name
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        $copied += $target
    }
}

Write-Host ''
Write-Ok '打包完成，产物：'
foreach ($file in $copied) {
    Write-Host ('      ' + $file.Replace($Root + '\', '') + '   ' + [math]::Round((Get-Item $file).Length / 1MB, 1) + ' MB')
}
if ($copied.Count -eq 0) {
    Write-Warn '没有找到可分发文件，请检查上面的编译输出'
}
Write-Host ''
Write-Info '免安装版双击 exe 即可运行；它只是一个外壳，图片仍由后端服务提供：'
Write-Info '  本机后端：先跑 start.cmd（或 .\start.ps1），客户端默认连 127.0.0.1:8080'
Write-Info '  远程后端：打包时用 -ApiBase，或运行时设置环境变量 QXFLY_IMAGE_HOST_API_BASE'
Write-Host ''
