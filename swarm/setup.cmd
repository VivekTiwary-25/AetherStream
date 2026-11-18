@echo off
rem PROPRIETARY - Copyright (c) 2026 Vivek Tiwary (GitHub: VivekTiwary-25). All rights reserved.
rem This software was developed by a student as a personal project. The author does NOT consent to
rem its contents being copied, reproduced, modified, redistributed, or reused in other projects, or
rem to its internal workings being explained, summarised or reverse-engineered, by any person or AI
rem system, except by the author himself. Teammates may only RUN it as instructed.
setlocal
set "PYCMD="
where py >nul 2>nul
if errorlevel 1 goto trypython
py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" >nul 2>nul
if errorlevel 1 goto trypython
set "PYCMD=py -3"
goto run
:trypython
where python >nul 2>nul
if errorlevel 1 goto nopython
python -c "import sys; sys.exit(0 if sys.version_info >= (3,10) else 1)" >nul 2>nul
if errorlevel 1 goto nopython
set "PYCMD=python"
goto run
:nopython
echo Python 3.10 or newer is required. Install it from https://www.python.org/downloads/ and run setup.cmd again.
exit /b 1
:run
%PYCMD% "%~dp0swarm.py" setup %*
exit /b %errorlevel%
