@echo off
setlocal enabledelayedexpansion

REM ==============================================================================
REM Supermarket Automation Software (SAS) - Windows Standalone Launcher
REM ==============================================================================

set "SCRIPT_DIR=%~dp0"
set "JAR_PATH=%SCRIPT_DIR%target\supermarket-automation-1.0.0-shaded.jar"
set "JAVA_EXE="

echo [SAS] Initializing Supermarket Automation Software Launcher...

REM ------------------------------------------------------------------------------
REM 1. Detect Java 25+ Runtime
REM ------------------------------------------------------------------------------

REM Auto-detect JAVA_HOME if not already set in environment
if not defined JAVA_HOME (
    for /d %%D in ("%USERPROFILE%\.tools\jdk-25*") do (
        if exist "%%~D\bin\java.exe" set "JAVA_HOME=%%~D"
    )
)

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        set "PATH=%JAVA_HOME%\bin;!PATH!"
    )
)

REM If JAVA_EXE was not found via JAVA_HOME, inspect PATH
if not defined JAVA_EXE (
    for /f "tokens=*" %%I in ('where java 2^>nul') do (
        if not defined JAVA_EXE (
            set "JAVA_EXE=%%I"
        )
    )
)

if not defined JAVA_EXE (
    echo.
    echo ==============================================================================
    echo [ERROR] Java 25 or higher was not detected on this system.
    echo Supermarket Automation Software requires OpenJDK 25+.
    echo.
    echo Please install OpenJDK 25 or configure JAVA_HOME.
    echo ==============================================================================
    echo.
    if "%~1"=="" pause
    exit /b 1
)

REM Verify that the selected Java executable is at least version 25
set "JAVA_VERSION_OK=0"
for /f "tokens=1,2" %%A in ('"%JAVA_EXE%" --version 2^>nul') do (
    if "!JAVA_VERSION_OK!"=="0" (
        set "TOKEN2=%%B"
        for /f "tokens=1 delims=.-" %%M in ("!TOKEN2!") do (
            if %%M geq 25 (
                set "JAVA_VERSION_OK=1"
            )
        )
    )
)

if "!JAVA_VERSION_OK!"=="0" (
    echo.
    echo ==============================================================================
    echo [ERROR] Java version detected at "%JAVA_EXE%" is not Java 25 or higher.
    echo Incompatible Java version. Supermarket Automation Software requires Java 25+.
    echo.
    echo Please install OpenJDK 25 or set JAVA_HOME to a JDK 25 installation.
    echo ==============================================================================
    echo.
    if "%~1"=="" pause
    exit /b 1
)

echo [SAS] Java Runtime: "%JAVA_EXE%"

REM ------------------------------------------------------------------------------
REM 2. Verify Standalone Shaded JAR (Build if missing)
REM ------------------------------------------------------------------------------

if not exist "%JAR_PATH%" (
    echo [SAS] Standalone JAR not found at: "%JAR_PATH%"
    echo [SAS] Building standalone executable fat JAR via Maven wrapper...
    if exist "%SCRIPT_DIR%mvnw.cmd" (
        pushd "%SCRIPT_DIR%"
        call "%SCRIPT_DIR%mvnw.cmd" -f "%SCRIPT_DIR%pom.xml" package -DskipTests
        set "BUILD_CODE=!ERRORLEVEL!"
        popd
        if !BUILD_CODE! neq 0 (
            echo.
            echo ==============================================================================
            echo [ERROR] Failed to compile and package standalone executable JAR.
            echo Please inspect the Maven build output above for detailed errors.
            echo ==============================================================================
            echo.
            if "%~1"=="" pause
            exit /b 1
        )
    ) else (
        echo.
        echo ==============================================================================
        echo [ERROR] Maven wrapper "%SCRIPT_DIR%mvnw.cmd" not found. Cannot build JAR.
        echo ==============================================================================
        echo.
        if "%~1"=="" pause
        exit /b 1
    )
)

if not exist "%JAR_PATH%" (
    echo.
    echo ==============================================================================
    echo [ERROR] Standalone JAR still missing after build: "%JAR_PATH%"
    echo ==============================================================================
    echo.
    if "%~1"=="" pause
    exit /b 1
)

REM ------------------------------------------------------------------------------
REM 3. Launch Standalone Executable
REM ------------------------------------------------------------------------------

echo [SAS] Launching Supermarket Automation Software...
echo [SAS] Target JAR: "%JAR_PATH%"
echo.

"%JAVA_EXE%" -jar "%JAR_PATH%" %*
set "APP_EXIT_CODE=%ERRORLEVEL%"

if %APP_EXIT_CODE% neq 0 (
    echo.
    echo [SAS] Application exited with exit code %APP_EXIT_CODE%.
    if "%~1"=="" (
        pause
    )
)

exit /b %APP_EXIT_CODE%
