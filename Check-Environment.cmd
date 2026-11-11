@echo off
setlocal
chcp 65001 >nul
pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0Check-Environment.ps1"
set "mallCheckExit=%ERRORLEVEL%"
echo.
pause
exit /b %mallCheckExit%
