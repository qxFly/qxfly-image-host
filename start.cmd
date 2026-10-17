@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem qxfly-image-host one-click launcher (double-click me)
rem extra arguments are passed through, for example:
rem   start.cmd -Action status
rem   start.cmd -Action stop
rem   start.cmd -Mode prod
rem   start.cmd -Database h2
rem   start.cmd -BackendPort 8081 -WebPort 5174
rem
rem NOTE 1: keep this file ASCII-only. cmd.exe parses .cmd files with the active code
rem page; a mid-file chcp makes it misread non-ASCII bytes, which splits words apart and
rem runs garbage commands. Chinese console output belongs in the .ps1 scripts.
rem
rem NOTE 2: the implementation lives in scripts\start-services.ps1 (all .ps1 scripts are kept in
rem scripts\); this file stays at the project root so it can be double-clicked.
rem
rem NOTE 3: -File is used on purpose: it is the standard invocation and it propagates the
rem script exit code (dot-sourcing swallowed it, so scripted checks could never fail).

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-services.ps1" %*
set "RC=%ERRORLEVEL%"

rem keep the script exit code (a bare 'goto :eof' can lose it)
if not "%~1"=="" exit /b %RC%
echo.
echo Press any key to close this window ...
pause >nul
exit /b %RC%
