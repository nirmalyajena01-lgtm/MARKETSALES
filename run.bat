@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ==============================================================================
echo        SUPERMARKET AUTOMATION SOFTWARE (SAS) - MARKETLINE
echo ==============================================================================
echo.
echo   Launching Web Application with:
echo     - Clean Single-Card Operator Sign-In
echo     - Dual Graphic Canvas and Math Captcha Challenge
echo     - Two-Step Authentication (2FA) with OTP Verification
echo.
echo   Local Address: http://127.0.0.1:8765/
echo   Auto-opening browser...
echo ==============================================================================
echo.

start "" http://127.0.0.1:8765/
call "%~dp0run-sas.bat" --web 8765

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Server process stopped.
    pause
)
