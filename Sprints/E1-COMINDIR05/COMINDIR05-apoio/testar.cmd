@echo off
chcp 65001 >nul
title COMINDIR05 - Ensaios
py -3 -X utf8 "%~dp0integracao.py"
set "comindirExit=%errorlevel%"
echo.
pause
exit /b %comindirExit%
