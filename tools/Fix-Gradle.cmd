@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Fix-Gradle.ps1" -Build
pause
