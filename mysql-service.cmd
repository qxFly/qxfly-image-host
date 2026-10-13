@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem PicHost 数据库管家（Windows）
rem   mysql-service.cmd status            查看服务状态
rem   mysql-service.cmd install           注册服务 + 开机自启
rem   mysql-service.cmd start|stop|restart
rem   mysql-service.cmd autostart on|off
rem   mysql-service.cmd path add|machine|remove
rem   mysql-service.cmd init -User root -Password 你的密码
rem   mysql-service.cmd console

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\mysql-service.ps1" %*

if "%~1"=="" (
  echo.
  echo 按任意键关闭窗口 ...
  pause >nul
)
