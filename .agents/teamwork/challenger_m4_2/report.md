# Adversarial Challenge Report: Milestone 4 Packaging & Windows Standalone Executable

**Agent:** `challenger_m4_2` (Empirical Challenger)  
**Parent Agent:** `orchestrator_2` (`b1867780-0866-4bc6-a559-811ca0300e3f`)  
**Target Artifacts:** `run-sas.bat`, `target/supermarket-automation-1.0.0-shaded.jar`, `Launcher.java`, `pom.xml`  
**Date:** 2026-10-02  
**Overall Risk Assessment:** **MEDIUM-HIGH**  
**Final Verdict:** **REQUEST_CHANGES**

---

## 1. Executive Summary

Milestone 4 packaging was subjected to empirical adversarial testing across 5 challenge dimensions:
1. Execution from external working directories (`C:\`, `%TEMP%`).
2. Parameter passing with spaces, quotes, special characters, and multiple flags.
3. `%ERRORLEVEL%` exit code propagation across valid, headless, and error scenarios.
4. Self-healing auto-packaging when target directory or JAR is absent.
5. Automated test suite execution (`.\mvnw.cmd test`).

**Key Takeaways:**
- **Unit and Service Tests:** 194/194 tests pass cleanly with 0 failures, 0 errors, 0 skipped.
- **Normal Execution:** When the shaded fat JAR exists, `run-sas.bat` executes cleanly with accurate exit codes (`0` for `--smoke-test`, `0` for `--version`, `1` for unknown options).
- **CRITICAL DEFECT 1:** When the shaded JAR is absent and `run-sas.bat` is executed from any external working directory (e.g. `C:\Users\User\AppData\Local\Temp` or `C:\`), the self-healing packaging fails immediately with `MissingProjectException` because `mvnw.cmd` is invoked without specifying `-f "%SCRIPT_DIR%pom.xml"` or switching directory to `%SCRIPT_DIR%`.
- **CRITICAL DEFECT 2:** On build failure or pre-launch validation failure, `run-sas.bat` issues an unconditional `pause`, causing automated test runners, CI pipelines, and scripts to hang indefinitely until killed.

---

## 2. Adversarial Challenge Results Matrix

| Challenge ID | Scenario | Expected Behavior | Actual Behavior | Result |
|---|---|---|---|---|
| **TC-01** | Test Suite Regression (`.\mvnw.cmd test`) | 194 passing tests, 0 failures | 194 run, 0 failures, 0 skipped (30.6s) | **PASS** |
| **TC-02** | Smoke Test Exit Code (`run-sas.bat --smoke-test`) | All 5 checks pass, exit code 0 | All 5 checks pass, exit code 0 | **PASS** |
| **TC-03** | Version Query Exit Code (`run-sas.bat --version`) | Displays version & JVM info, exit code 0 | Displays version & JVM info, exit code 0 | **PASS** |
| **TC-04** | Invalid CLI Flag (`run-sas.bat --invalid-flag`) | Displays error & help, exit code 1 | Displays error & help, exit code 1 | **PASS** |
| **TC-05** | Multiple CLI Flags (`run-sas.bat --version --help`) | Precedence handled, exit code 0 | Version displayed, exit code 0 | **PASS** |
| **TC-06** | Arguments with Spaces (`run-sas.bat "--invalid flag with spaces"`) | Preserves full token with spaces | Preserved as single token, exit code 1 | **PASS** |
| **TC-07** | Arguments with Special Chars (`run-sas.bat "--foo=bar;baz,qux"`) | Passes through without shell mangling | Passed cleanly to JVM, exit code 1 | **PASS** |
| **TC-08** | External CWD Execution with JAR (`C:\`, `%TEMP%`) | Launches successfully from arbitrary CWD | Successfully launches, exit code 0 | **PASS** |
| **TC-09** | Self-Healing from Project Root (Missing JAR) | Rebuilds JAR via Maven and launches | Rebuilds JAR (7.8s) and launches, exit code 0 | **PASS** |
| **TC-10** | **Self-Healing from External CWD (Missing JAR)** | **Rebuilds JAR from `%SCRIPT_DIR%` and launches** | **FAILS: Maven looks for POM in caller CWD, fails, hangs on `pause`** | **FAIL** |
| **TC-11** | **Unconditional Pause on Error Paths** | **Non-interactive / CLI mode should exit immediately** | **HANGS: Waits for keyboard input at `pause`** | **FAIL** |

---

## 3. Detailed Vulnerability Analyses

### Vulnerability 1: Self-Healing Build Fails when Executed Outside Project Root

#### Observation
When `target/supermarket-automation-1.0.0-shaded.jar` is missing and `run-sas.bat` is executed with its absolute path from an external working directory (such as `%TEMP%` or `C:\`):
```cmd
cd C:\Users\User\AppData\Local\Temp
call C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\run-sas.bat --version
```

Verbatim terminal output:
```
[SAS] Initializing Supermarket Automation Software Launcher...
[SAS] Java Runtime: "C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe"
[SAS] Standalone JAR not found at: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"
[SAS] Building standalone executable fat JAR via Maven wrapper...
[INFO] Scanning for projects...
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  0.159 s
[INFO] Finished at: 2026-10-03T03:02:48+05:30
[INFO] ------------------------------------------------------------------------
[ERROR] The goal you specified requires a project to execute but there is no POM in this directory (C:\Users\User\AppData\Local\Temp). Please verify you invoked Maven from the correct directory. -> [Help 1]

==============================================================================
[ERROR] Failed to compile and package standalone executable JAR.
Please inspect the Maven build output above for detailed errors.
==============================================================================

Press any key to continue . . . 
```

#### Root Cause Analysis
In `run-sas.bat` lines 101-103:
```batch
if exist "%SCRIPT_DIR%mvnw.cmd" (
    call "%SCRIPT_DIR%mvnw.cmd" package -DskipTests
    if !ERRORLEVEL! neq 0 (
```
While `mvnw.cmd` is correctly referenced via `%SCRIPT_DIR%`, Maven wrapper executes in the caller's current working directory (`CWD`). Unless `-f "%SCRIPT_DIR%pom.xml"` is supplied or the working directory is switched to `%SCRIPT_DIR%`, Maven searches for `pom.xml` in `CWD`. When `CWD` is not the repository root, Maven terminates with `MissingProjectException`.

#### Blast Radius
Any user or automated system launching `run-sas.bat` from a desktop shortcut, PowerShell prompt, scheduled task, or script located in another directory on a fresh clone or clean checkout will experience complete failure to launch.

#### Remediation
In `run-sas.bat`, wrap the Maven build in `pushd "%SCRIPT_DIR%"` and `popd` (and optionally pass `-f "%SCRIPT_DIR%pom.xml"`):
```batch
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
)
```

---

### Vulnerability 2: Unconditional `pause` on Error Paths Causes Scripts/CI to Hang

#### Observation
In `run-sas.bat`, lines 62, 88, 110, 119, and 130 contain unconditional `pause` instructions:
- Line 62: `if not defined JAVA_EXE ( ... pause & exit /b 1 )`
- Line 88: `if "!JAVA_VERSION_OK!"=="0" ( ... pause & exit /b 1 )`
- Line 110: `if !ERRORLEVEL! neq 0 ( ... pause & exit /b 1 )`
- Line 119: `else ( ... echo Maven wrapper not found ... pause & exit /b 1 )`
- Line 130: `if not exist "%JAR_PATH%" ( ... pause & exit /b 1 )`

In contrast, line 148 conditionally guards the pause:
```batch
if %APP_EXIT_CODE% neq 0 (
    echo.
    echo [SAS] Application exited with exit code %APP_EXIT_CODE%.
    if "%~1"=="" (
        pause
    )
)
```

#### Root Cause Analysis
Lines 62, 88, 110, 119, and 130 do not check `if "%~1"==""`. Consequently, whenever any pre-launch check fails while CLI arguments (`--smoke-test`, `--version`, etc.) are passed, the script prompts `Press any key to continue . . .` and blocks execution indefinitely. During our test execution of task-52, the background task hung indefinitely until forcibly terminated.

#### Blast Radius
Automated testing tools, CI pipelines, headless health checkers, and deployment scripts calling `run-sas.bat` will deadlock on any configuration or build error instead of returning a prompt non-zero exit code.

#### Remediation
Guard each pre-launch `pause` statement with `if "%~1"=="" ( pause )`, exactly as implemented on line 148.

---

## 4. Verification Evidence

### 4.1 Automated Test Suite
- Command: `.\mvnw.cmd test`
- Result: 194 run, 0 failures, 0 errors, 0 skipped. `BUILD SUCCESS`.

### 4.2 Standalone Smoke Test
- Command: `cmd.exe /c "call run-sas.bat --smoke-test"`
- Result: 5/5 check stages pass, exit code `0`.

### 4.3 Version Query
- Command: `cmd.exe /c "call run-sas.bat --version"`
- Result: App version 1.0.0, Java 21.0.6, exit code `0`.

### 4.4 Exit Code Propagation
- Command: `cmd.exe /c "call run-sas.bat --invalid-flag"`
- Result: Outputs `Unknown option: --invalid-flag`, prints usage, exit code `1`.

### 4.5 Self-Healing Auto-Packaging
- Tested from project root: Shaded fat JAR automatically compiled and launched with exit code `0`.
- Tested from external CWD (`%TEMP%`): Failed with `MissingProjectException` and hung at `pause`.

---

## 5. Conclusion & Recommendation

The packaging and launcher design is 90% solid: fat JAR construction with JavaFX runtime, embedded SQLite drivers, schema migrations, and headless CLI smoke testing work excellently.

However, because Milestone 4 specifically mandates standalone Windows execution and self-healing auto-packaging under arbitrary environments, the two defects identified above directly prevent reliable execution from external working directories and non-interactive scripts.

**Verdict: REQUEST_CHANGES.**  
Worker `worker_m4` should apply the recommended mitigations to `run-sas.bat` and re-verify.
