@echo off
chcp 65001 >nul
rem 一键启动（Windows 开发机）。
rem 真正的逻辑在 deploy\local-windows\start-local.ps1 里；这个文件只是双击入口，参数原样透传。
rem 例：start-local.bat -SkipBuild     start-local.bat -Runtime noop
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\local-windows\start-local.ps1" %*
set "CODE=%ERRORLEVEL%"
echo.
if "%CODE%"=="0" (
  echo 服务在后台跑着，本窗口可以直接关掉。想停掉全部服务：双击 stop-local.bat
) else (
  echo 启动没能完成（退出码 %CODE%）：上面的提示就是原因，日志在 deploy\local-windows\.local\logs
)
echo.
pause
endlocal & exit /b %CODE%
