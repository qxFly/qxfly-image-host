@echo off
chcp 65001 >nul
setlocal

rem In-project MySQL client entry point: just type mysql in the project root,
rem no need to add the MySQL bin directory to PATH first.
rem   mysql                 open the interactive shell
rem   mysql -uroot -p        normal client usage (arguments are passed through)
rem   mysql -e "show databases;"
rem
rem NOTE: keep this file ASCII-only (see package.cmd for why).

set "MYSQL_EXE="
for %%D in (
  "C:\Program Files\MySQL\MySQL Server 8.0\bin"
  "C:\Program Files\MySQL\MySQL Server 8.4\bin"
  "C:\Program Files\MySQL\MySQL Server 8.5\bin"
  "C:\Program Files\MySQL\MySQL Server 5.7\bin"
) do (
  if not defined MYSQL_EXE if exist "%%~D\mysql.exe" set "MYSQL_EXE=%%~D\mysql.exe"
)

if not defined MYSQL_EXE (
  for /f "delims=" %%P in ('where mysql.exe 2^>nul') do (
    if not defined MYSQL_EXE set "MYSQL_EXE=%%P"
  )
)

if not defined MYSQL_EXE (
  echo [x] mysql.exe was not found
  echo     run 'mysql-service.cmd status' to check the install location
  exit /b 1
)

"%MYSQL_EXE%" %*
exit /b %ERRORLEVEL%
