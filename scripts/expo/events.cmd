@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0events.ps1" %*
exit /b %ERRORLEVEL%
