@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\BuildVM\cards\ton1111\tools\Runner.ps1
exit /b %ERRORLEVEL%
