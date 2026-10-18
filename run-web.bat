@echo off
title Marketline Web Server
cd /d "%~dp0"
set "JAVA_HOME=C:\Users\User\.tools\jdk-25.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ===================================================
echo   Starting Marketline Web Server on Port 8080...
echo   Keep this window open to view live 2FA OTP codes!
echo ===================================================
echo.

java -cp target\supermarket-automation-1.0.0-shaded.jar com.supermarket.Launcher --web 8080
pause
