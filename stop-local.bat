@echo off
chcp 65001 >nul
rem 一键停止：把 start-local.bat 起来的服务全停掉（含前端）。
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\local-windows\stop-local.ps1" %*
set "CODE=%ERRORLEVEL%"
echo.
pause
endlocal & exit /b %CODE%
