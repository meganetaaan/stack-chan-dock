@echo off
chcp 65001 >nul
cd /d "%~dp0apps\windows-local-voice"
node src\cli.mjs
pause
