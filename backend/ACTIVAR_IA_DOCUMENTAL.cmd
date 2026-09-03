@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0activar_ia_documental.ps1"
if errorlevel 1 (
  echo.
  echo No se pudo completar la activacion. La ventana queda abierta para mostrar el motivo.
  pause
)
endlocal
