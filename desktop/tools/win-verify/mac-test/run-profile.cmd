@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0Start-Isolated.ps1" %*
exit /b %errorlevel%
