@echo off
chcp 65001 >nul
setlocal

rem 项目内的 mysql 客户端入口：在项目根目录直接敲 mysql 即可，
rem 不需要事先把 MySQL 的 bin 目录加进 PATH。
rem   mysql                进入交互命令行
rem   mysql -uroot -p      正常客户端用法（参数原样透传）
rem   mysql -e "show databases;"

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
  echo [x] 未找到 mysql.exe
  echo     请执行 mysql-service.cmd status 检查安装位置
  exit /b 1
)

"%MYSQL_EXE%" %*
exit /b %ERRORLEVEL%
