@echo off
chcp 65001 >nul
title COMINDIR05 - Painel
py -3 -X utf8 "%~dp0painel.py"
set "comindirExit=%errorlevel%"
echo.
echo Carrega numa tecla para fechar esta janela.
pause >nul
exit /b %comindirExit%
