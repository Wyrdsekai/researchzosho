@echo off
rem zosho: the short form of researchzosho.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0researchzosho.ps1" %*
exit /b %ERRORLEVEL%
