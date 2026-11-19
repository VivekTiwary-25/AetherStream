@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0worker.ps1" %*
exit /b %ERRORLEVEL%
