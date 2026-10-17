@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem qxfly-image-host database helper (Windows)
rem   mysql-service.cmd status            show service status
rem   mysql-service.cmd install           register service + autostart
rem   mysql-service.cmd start|stop|restart
rem   mysql-service.cmd autostart on|off
rem   mysql-service.cmd path add|machine|remove
rem   mysql-service.cmd init -User root -Password YOUR_PASSWORD
rem   mysql-service.cmd console
rem
rem NOTE: keep this file ASCII-only (see package.cmd for why).

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\manage-mysql-service.ps1" %*
set "RC=%ERRORLEVEL%"

if "%~1"=="" (
  echo.
  echo Press any key to close this window ...
  pause >nul
)
exit /b %RC%
