@echo off
setlocal
where ipfs >nul 2>nul
if errorlevel 1 (
  echo Kubo nao encontrado no PATH. Fecha e volta a abrir o terminal.
  pause
  exit /b 1
)
echo Mantem esta janela aberta durante a simulacao. Ctrl+C para terminar.
ipfs daemon
if errorlevel 1 pause
