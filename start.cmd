@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem PicHost 一键启动（双击本文件即可）
rem 支持透传参数，例如：  start.cmd -Action stop
rem                       start.cmd -Database h2
rem                       start.cmd -Target desktop

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*

if not "%~1"=="" goto :eof
echo.
echo 按任意键关闭窗口 ...
pause >nul
