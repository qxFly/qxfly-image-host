@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem PicHost 一键打包（Windows）
rem   package.cmd            构建并组装两个平台的包
rem   package.cmd -SkipBuild 跳过编译，只用现有产物组装
rem   package.cmd -Zip       额外生成 zip

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package.ps1" %*

if "%~1"=="" (
  echo.
  echo 按任意键关闭窗口 ...
  pause >nul
)
