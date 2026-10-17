#Requires -Version 5.1
<#
.SYNOPSIS
    qxfly-image-host 服务端包打包向导：选择「部署到哪个环境」和「是否附带运行时」。
.DESCRIPTION
    不带参数时进交互菜单；也可以直接给参数（给自动化/CI 用）：
        -Env linux|win|both      要部署到哪个环境（决定包含哪套入口脚本）
        -Runtime none|linux|win|both   是否附带便携运行时（默认按 -Env 推导）
        -NoVerify                跳过打包后的冒烟测试
        -SkipBuild               不重新编译，只用现有产物重新组装
.EXAMPLE
    .\package-server.ps1                       # 交互选择
    .\package-server.ps1 -Env linux -Runtime none   # Linux 极简包（约 90 MB）
    .\package-server.ps1 -Env win -Runtime win      # Windows 测试包
#>
[CmdletBinding()]
param(
    [ValidateSet('linux', 'win', 'both')][string]$Env = '',
    [ValidateSet('none', 'linux', 'win', 'both')][string]$Runtime = '',
    # 默认做冒烟测试；-NoVerify 跳过（-Verify 也接受，写了就明确要做）
    [switch]$NoVerify,
    [switch]$Verify,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ScriptsDir = $PSScriptRoot
$Root       = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'common-library.ps1')

# 体积参考（实测值，用于给用户一个预期）
$sizeHint = @{
    'linux|linux' = '约 290 MB'; 'linux|none' = '约 90 MB'
    'win|win'     = '约 460 MB'; 'win|none'   = '约 90 MB'
    'both|both'   = '约 670 MB'; 'both|linux' = '约 290 MB'
    'both|win'    = '约 460 MB'; 'both|none'  = '约 90 MB'
}

function Read-Choice {
    param([string]$Prompt, [string[]]$Allowed)
    while ($true) {
        $answer = (Read-Host $Prompt).Trim().ToLower()
        if ($Allowed -contains $answer) { return $answer }
        Write-Warn ('请输入：' + ($Allowed -join ' / '))
    }
}

function Show-Plan {
    param([string]$EnvChoice, [string]$RuntimeChoice)
    $envText = switch ($EnvChoice) {
        'linux' { 'Linux / Ubuntu（只放 start.sh 等 Linux 入口）' }
        'win'   { 'Windows（只放 start.cmd 等 Windows 入口，用于本机测试）' }
        default { '两个都要（Linux + Windows 入口）' }
    }
    $rtText = switch ($RuntimeChoice) {
        'none'  { '不带运行时（目标机需自备 JDK 17+）' }
        'linux' { '带 Linux 便携运行时（runtime/_cache 归档）' }
        'win'   { '带 Windows 便携 JDK（runtime/win-x64）' }
        default { '两套运行时都带' }
    }
    $key = $EnvChoice + '|' + $RuntimeChoice
    $size = if ($sizeHint.ContainsKey($key)) { $sizeHint[$key] } else { '（未知组合，体积以实际为准）' }
    Write-Host ''
    Write-Banner '即将打包'
    Write-Info ('环境     : ' + $envText)
    Write-Info ('运行时   : ' + $rtText)
    Write-Info ('预计体积 : ' + $size)
    Write-Info '产物目录 : dist\qxfly-image-host-server'
    Write-Host ''
}

function Invoke-Wizard {
    Write-Banner '服务端包打包向导'
    Write-Host '   打包成什么环境用？（决定包含哪套入口脚本）'
    Write-Host '     1) Linux / Ubuntu  —— 只放 start.sh / toolbox.sh / mysql.sh（上传服务器用）'
    Write-Host '     2) Windows        —— 只放 start.cmd 等，用于在本机跑起来测试'
    Write-Host '     3) 两个都要        —— 一个包两边都能跑（体积最大）'
    $envChoice = Read-Choice -Prompt '   请选择 [1-3]' -Allowed @('1', '2', '3')
    $envMap = @{ '1' = 'linux'; '2' = 'win'; '3' = 'both' }
    $envValue = $envMap[$envChoice]

    Write-Host ''
    Write-Host '   要不要把便携运行时（JDK）一起打进去？'
    Write-Host '     1) 带上           —— 目标机不用装 Java，开箱即用（体积大）'
    Write-Host '     2) 不带           —— 目标机自备 JDK 17+（体积最小，约 90 MB）'
    $rtChoice = Read-Choice -Prompt '   请选择 [1-2]' -Allowed @('1', '2')
    $runtimeValue = if ($rtChoice -eq '2') { 'none' } else { $envValue }

    Write-Host ''
    Write-Host '   打包完自动做一次冒烟测试（用包内 jar 起临时端口验证能跑）？'
    Write-Host '     1) 要（推荐，多花 20 秒左右）'
    Write-Host '     2) 不要'
    $verifyChoice = Read-Choice -Prompt '   请选择 [1-2]' -Allowed @('1', '2')

    Show-Plan -EnvChoice $envValue -RuntimeChoice $runtimeValue
    $confirm = (Read-Host '   回车开始打包，输入 n 取消').Trim().ToLower()
    if ($confirm -eq 'n') { Write-Warn '已取消'; return }

    $named = @{ Target = 'server'; Env = $envValue; Runtime = $runtimeValue }
    if ($verifyChoice -eq '1') { $named['Verify'] = $true }
    if ($SkipBuild) { $named['SkipBuild'] = $true }
    Invoke-Package -Named $named
}

function Invoke-Package {
    param([hashtable]$Named = @{})
    $script = Join-Path $ScriptsDir 'package-release.ps1'
    # 注意：只有哈希表 splat 才是「命名传参」；数组 splat 是按位置传参，
    # 会把 -Target 当成一个位置参数值，导致 ValidateSet 报错。
    if ($Named.Count -gt 0) { & $script @Named } else { & $script }
}

# ================================================================== 入口
if (-not $Env -and -not $Runtime) {
    Invoke-Wizard
    exit 0
}

# 直接给参数：按 -Env 推导运行时（未显式指定时），再转发
$finalEnv = if ($Env) { $Env } else { 'both' }
$finalRuntime = if ($Runtime) { $Runtime } else { $finalEnv }
$named = @{ Target = 'server'; Env = $finalEnv; Runtime = $finalRuntime }
if (-not $NoVerify) { $named['Verify'] = $true }
if ($Verify) { $named['Verify'] = $true }
if ($SkipBuild) { $named['SkipBuild'] = $true }
Show-Plan -EnvChoice $finalEnv -RuntimeChoice $finalRuntime
Invoke-Package -Named $named
