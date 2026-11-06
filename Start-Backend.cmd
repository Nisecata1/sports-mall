@echo off
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start-Backend.ps1"
if errorlevel 1 pause
