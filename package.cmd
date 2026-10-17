@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem qxfly-image-host one-click packager (Windows)
rem   package.cmd            build and assemble both platform packages
rem   package.cmd -SkipBuild skip compiling, only re-assemble from current artifacts
rem   package.cmd -Zip       also produce zip archives
rem
rem NOTE 1: keep this file ASCII-only. cmd.exe parses .cmd files with the active code
rem page; a mid-file chcp makes it misread non-ASCII bytes, which splits words apart
rem and runs garbage like "ckage.cmd". Chinese console output belongs in the .ps1.
rem
rem NOTE 2: -File is used on purpose (it propagates the script exit code). The old
rem 'stopped after assembling Windows package and exited 0' failure was NOT caused by
rem -File: it came from capturing external-program output through pipes plus a lost
rem UTF-8 BOM. Both are fixed (see scripts/README.md).

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-release.ps1" %*
set "RC=%ERRORLEVEL%"

if "%~1"=="" (
  echo.
  echo Press any key to close this window ...
  pause >nul
)
exit /b %RC%
