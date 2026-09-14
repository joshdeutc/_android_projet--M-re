@echo off
chcp 65001 >nul
echo.
echo ==============================================================================
echo  Mise a jour de Custos (SelfControl Ultimate)
echo ==============================================================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update_app.ps1"

echo.
pause
