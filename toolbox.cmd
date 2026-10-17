@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem qxfly-image-host toolbox (one entry for everything except the launcher)
rem   toolbox.cmd                   interactive menu (numbered choices)
rem   toolbox.cmd status            also: start | stop | restart
rem   toolbox.cmd package [-Zip]    build release packages
rem   toolbox.cmd desktop           build the Tauri desktop client
rem   toolbox.cmd runtime           prepare portable JDK + Node
rem   toolbox.cmd db status         database helper (install/start/stop/init/...)
rem   toolbox.cmd check             script self-check (ASCII / BOM / syntax)
rem   toolbox.cmd diag              diagnostics report
rem   toolbox.cmd clean             cleanup caches and build output
rem   toolbox.cmd config            show effective config and URLs
rem   toolbox.cmd logs [name]       tail logs
rem   toolbox.cmd deploy            generate nginx + systemd templates
rem
rem NOTE 1: keep this file ASCII-only (cmd.exe parses .cmd with the active code page;
rem a mid-file chcp makes it split words and run garbage commands).
rem NOTE 2: -File is used on purpose. With '-Command ". script"' (dot-source) or '-Command "& script"'
rem the script's exit code was swallowed, so scripted checks could never fail. -File propagates it.

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\toolbox-menu.ps1" %*
set "RC=%ERRORLEVEL%"

rem keep the script exit code (a bare 'goto :eof' can lose it)
if not "%~1"=="" exit /b %RC%
echo.
echo Press any key to close this window ...
pause >nul
exit /b %RC%
