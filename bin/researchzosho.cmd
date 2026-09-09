@echo off
rem ResearchZosho (研究蔵書) on Windows. The work is in researchzosho.ps1 beside this file.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0researchzosho.ps1" %*
exit /b %ERRORLEVEL%
