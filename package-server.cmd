@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem qxfly-image-host one-click SERVER package builder (double-click me)
rem
rem No arguments -> interactive wizard: choose the target environment and whether a portable
rem                 runtime is bundled, instead of always building everything.
rem With args    -> non-interactive, for example:
rem   package-server.cmd -Env linux                 Linux/Ubuntu entry points only (smallest)
rem   package-server.cmd -Env linux -Runtime none   no runtime bundled (target needs JDK 17+)
rem   package-server.cmd -Env win -Runtime win       Windows entry points + portable JDK
rem   package-server.cmd -Env both                  both entry points, both runtimes
rem   package-server.cmd -NoVerify -SkipBuild       skip smoke test / skip compiling
rem
rem Result: dist\qxfly-image-host-server , one flat folder.
rem   -Env linux -> upload it to the server and run  ./start.sh -Mode prod
rem   -Env win   -> double-click start.cmd on Windows
rem   qxfly-image-host.jar sits at the folder root; DEPLOY-README.txt explains every file.
rem
rem NOTE 1: keep this file ASCII-only. cmd.exe parses .cmd files with the active code
rem page; non-ASCII bytes get split apart and run as garbage commands.
rem NOTE 2: never capture this script's output through a pipe (for example
rem   package-server.cmd -SkipBuild ^| Out-String). Piping an external program's output
rem   kills the process partway in some environments and leaves a half-built package.

set "PSEXE=powershell"
where pwsh >nul 2>nul && set "PSEXE=pwsh"

%PSEXE% -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-server.ps1" %*
set "RC=%ERRORLEVEL%"

rem Completeness guard. BUILD-INFO.txt / DEPLOY-README.txt / the jar are written at the very
rem end of a successful run, so a missing one means the build died partway. Fail loudly
rem instead of letting a half-built folder look finished (and get uploaded).
set "PKG=%~dp0dist\qxfly-image-host-server"
set "INCOMPLETE="
if not exist "%PKG%\BUILD-INFO.txt" set "INCOMPLETE=1"
if not exist "%PKG%\DEPLOY-README.txt" set "INCOMPLETE=1"
if not exist "%PKG%\qxfly-image-host.jar" set "INCOMPLETE=1"
if defined INCOMPLETE (
  echo.
  echo [x] "%PKG%" is INCOMPLETE - the packaging run died partway.
  echo     Do NOT upload it. Re-run WITHOUT piping the output:
  echo         package-server.cmd
  if "%RC%"=="0" set "RC=1"
)

if "%~1"=="" (
  echo.
  echo Press any key to close this window ...
  pause >nul
)
exit /b %RC%
