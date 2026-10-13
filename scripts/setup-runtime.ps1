#Requires -Version 5.1
<#
.SYNOPSIS
    下载并解压 PicHost 自带的便携运行时（JDK + Node），落盘到项目根目录 runtime/ 下。

.DESCRIPTION
    除数据库之外，PicHost 运行所需的环境全部放在 <项目根>/runtime/：
        runtime/win-x64/jdk      Windows 便携 JDK 17
        runtime/win-x64/node     Windows 便携 Node 20 LTS
        runtime/linux-x64/jdk    Ubuntu 便携 JDK 17
        runtime/linux-x64/node   Ubuntu 便携 Node 20 LTS
    start.ps1 / start.sh 会优先使用这些运行时，找不到才回退系统 PATH。
    下载源按顺序重试，国内源优先。

.EXAMPLE
    .\scripts\setup-runtime.ps1                 # 拉取 win + linux 两套
    .\scripts\setup-runtime.ps1 -Platform win    # 只拉 Windows
    .\scripts\setup-runtime.ps1 -Force           # 重新下载覆盖
    .\scripts\setup-runtime.ps1 -KeepArchive     # 保留压缩包，便于离线拷贝到 Ubuntu
#>
[CmdletBinding()]
param(
    [ValidateSet('win', 'linux', 'all')]
    [string]$Platform = 'all',

    [switch]$Force,
    [switch]$KeepArchive
)

$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent $PSScriptRoot
$RuntimeDir = Join-Path $Root 'runtime'
$CacheDir   = Join-Path $RuntimeDir '_cache'

# ---------------------------------------------------------------- 清单
$AdoptiumMirror = 'https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64'

$Manifest = @{
    'win-x64' = @{
        Label = 'Windows x64'
        Items = @(
            @{
                Key     = 'jdk'
                Name    = 'Temurin JDK 17'
                Dir     = 'jdk'
                File    = 'jdk-win-x64.zip'
                Kind    = 'zip'
                Verify  = 'bin\java.exe'
                Urls    = @(
                    ($AdoptiumMirror + '/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'),
                    'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip'
                )
            },
            @{
                Key     = 'node'
                Name    = 'Node.js 20 LTS'
                Dir     = 'node'
                File    = 'node-win-x64.zip'
                Kind    = 'zip'
                Verify  = 'node.exe'
                Urls    = @(
                    'https://nodejs.org/dist/v20.18.1/node-v20.18.1-win-x64.zip',
                    'https://npmmirror.com/mirrors/node/v20.18.1/node-v20.18.1-win-x64.zip'
                )
            }
        )
    }
    'linux-x64' = @{
        Label = 'Linux x64'
        Items = @(
            @{
                Key     = 'jdk'
                Name    = 'Temurin JDK 17'
                Dir     = 'jdk'
                File    = 'jdk-linux-x64.tar.gz'
                Kind    = 'tar'
                Verify  = 'bin/java'
                Urls    = @(
                    ($AdoptiumMirror + '/linux/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz'),
                    'https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz'
                )
            },
            @{
                Key     = 'node'
                Name    = 'Node.js 20 LTS'
                Dir     = 'node'
                File    = 'node-linux-x64.tar.xz'
                Kind    = 'tar'
                Verify  = 'bin/node'
                Urls    = @(
                    'https://nodejs.org/dist/v20.18.1/node-v20.18.1-linux-x64.tar.xz',
                    'https://npmmirror.com/mirrors/node/v20.18.1/node-v20.18.1-linux-x64.tar.xz'
                )
            }
        )
    }
}

# ---------------------------------------------------------------- 输出
function Write-Step { param([string]$Text) Write-Host ('  [*] ' + $Text) -ForegroundColor Cyan }
function Write-Ok   { param([string]$Text) Write-Host ('  [+] ' + $Text) -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host ('  [!] ' + $Text) -ForegroundColor Yellow }
function Write-Fail { param([string]$Text) Write-Host ('  [x] ' + $Text) -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host ('      ' + $Text) -ForegroundColor DarkGray }

# ---------------------------------------------------------------- 下载
function Get-File {
    param([string[]]$Urls, [string]$Destination)

    if ((Test-Path $Destination) -and -not $Force) {
        Write-Info ('已存在，跳过下载：' + (Split-Path -Leaf $Destination))
        return
    }
    $partial = $Destination + '.part'
    foreach ($url in $Urls) {
        $host2 = 'unknown'
        try { $host2 = ([Uri]$url).Host } catch { }
        Write-Info ('下载 ' + $host2 + ' ...')
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $client = New-Object System.Net.WebClient
            $client.Headers.Add('User-Agent', 'PicHost-Setup')
            $client.DownloadFile($url, $partial)
            $client.Dispose()
            $size = (Get-Item $partial).Length
            if ($size -lt 1MB) { throw ('文件过小，疑似失败：' + $size + ' 字节') }
            Move-Item -Path $partial -Destination $Destination -Force
            Write-Ok ('完成 ' + [math]::Round($size / 1MB, 1) + ' MB')
            return
        } catch {
            Write-Warn ('该源失败：' + $_.Exception.Message)
            if (Test-Path $partial) { Remove-Item $partial -Force -ErrorAction SilentlyContinue }
        }
    }
    throw ('所有下载源都失败：' + ($Urls -join ' , '))
}

# ---------------------------------------------------------------- 解压
function Expand-Artifact {
    param([string]$Archive, [string]$Kind, [string]$Target, [string]$Expected)

    $parentDir = Split-Path -Parent $Target
    if (-not (Test-Path $parentDir)) { New-Item -ItemType Directory -Force -Path $parentDir | Out-Null }
    $before = @(Get-ChildItem -LiteralPath $parentDir | Select-Object -ExpandProperty Name)
    try {
        # Windows 10 1803+ 自带 bsdtar，zip / tar.gz / tar.xz 都能解，
        # 且比 .NET 的 ZipFile.ExtractToDirectory 快很多（300MB 约 1.3 秒）。
        # 注意：ZipFile.ExtractToDirectory 会在 JDK 的 bin 目录 ucrtbase.dll 上误报"拒绝访问"。
        # 直接解到目标父目录，再把压缩包自带的顶层目录改名。
        # 不走深层临时目录：bsdtar 在超长路径（\\?\ 前缀）下创建 ucrtbase.dll 会失败。
        $tar = Get-Command tar.exe -ErrorAction SilentlyContinue
        if ($tar) {
            & $tar.Source -xf $Archive -C $parentDir
            if ($LASTEXITCODE -ne 0) { throw ('tar 解压失败，退出码 ' + $LASTEXITCODE) }
        } elseif ($Kind -eq 'zip') {
            Add-Type -AssemblyName System.IO.Compression.FileSystem
            [System.IO.Compression.ZipFile]::ExtractToDirectory($Archive, $parentDir)
        } else {
            throw '需要 tar.exe 才能解压 tar.gz / tar.xz，请使用 Windows 10 1803 及以上版本'
        }

        $after = @(Get-ChildItem -LiteralPath $parentDir | Where-Object { $before -notcontains $_.Name })
        if ($after.Count -ne 1) { throw ('压缩包结构不符合预期：新增 ' + $after.Count + ' 个顶层条目') }
        $root = $after[0].FullName

        if (Test-Path $Target) {
            Remove-Item $Target -Recurse -Force -ErrorAction SilentlyContinue
            if (Test-Path $Target) { & cmd.exe /c ('rmdir /s /q "' + $Target + '"') | Out-Null }
        }
        Move-Item -LiteralPath $root -Destination $Target

        if (-not (Test-Path (Join-Path $Target $Expected))) {
            throw ('解压后未找到 ' + $Expected + '，目录结构可能已变化')
        }
    } finally {
        # 目标父目录下若残留本次新增的顶层目录（异常路径），留待下次运行时覆盖
    }
}

# ---------------------------------------------------------------- 主流程
Write-Host ''
Write-Host '  ============================================================' -ForegroundColor DarkCyan
Write-Host '     PicHost 便携运行时安装' -ForegroundColor White
Write-Host '  ============================================================' -ForegroundColor DarkCyan

$targets = @()
if ($Platform -eq 'all') { $targets = @('win-x64', 'linux-x64') } else { $targets = @($Platform + '-x64') }

if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }

foreach ($platformKey in $targets) {
    $entry = $Manifest[$platformKey]
    Write-Host ''
    Write-Step ('准备 ' + $entry.Label + ' 运行时 -> runtime\' + $platformKey)
    foreach ($item in $entry.Items) {
        # Linux 运行时不在 Windows 上解压：tar 里的符号链接与权限位在 NTFS 上无法还原
        # （会报 Invalid argument）。这里只把压缩包备好，由 Ubuntu 上的 start.sh
        # 首次运行时原生解压到 runtime/linux-x64。
        if ($platformKey -eq 'linux-x64') {
            $archive = Join-Path $CacheDir $item.File
            Get-File -Urls $item.Urls -Destination $archive
            Write-Ok ($item.Name + ' 压缩包已就绪 -> runtime\_cache\' + $item.File)
            continue
        }

        $target = Join-Path (Join-Path $RuntimeDir $platformKey) $item.Dir
        if ((Test-Path (Join-Path $target $item.Verify)) -and -not $Force) {
            Write-Ok ($item.Name + ' 已就绪，跳过')
            continue
        }
        $archive = Join-Path $CacheDir $item.File
        Get-File -Urls $item.Urls -Destination $archive
        Write-Info ('解压 ' + $item.Name + ' ...')
        Expand-Artifact -Archive $archive -Kind $item.Kind -Target $target -Expected $item.Verify
        Write-Ok ($item.Name + ' -> runtime\' + $platformKey + '\' + $item.Dir)
        if (-not $KeepArchive) { Remove-Item $archive -Force -ErrorAction SilentlyContinue }
    }
}

# ---------------------------------------------------------------- 校验
Write-Host ''
Write-Step '校验'
$winJava = Join-Path $RuntimeDir 'win-x64\jdk\bin\java.exe'
if (Test-Path $winJava) {
    $ver = & $winJava -version 2>&1 | Select-Object -First 1
    Write-Ok ('Windows JDK : ' + $ver)
}
$winNode = Join-Path $RuntimeDir 'win-x64\node\node.exe'
if (Test-Path $winNode) {
    Write-Ok ('Windows Node : ' + (& $winNode -v 2>&1))
}
foreach ($p in @('linux-x64\jdk\bin\java', 'linux-x64\node\bin\node')) {
    $full = Join-Path $RuntimeDir $p
    if (Test-Path $full) { Write-Ok ('Linux 文件就位 : runtime\' + $p) }
}

Write-Host ''
Write-Ok '运行时安装完成'
Write-Info ('位置：' + $RuntimeDir)
if ($KeepArchive) { Write-Info ('压缩包保留在：' + $CacheDir + '（可直接拷到 Ubuntu 离线安装）') }
Write-Host ''
