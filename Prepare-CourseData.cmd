@echo off
cd /d "%~dp0"
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Prepare-CourseData.ps1"
pause
