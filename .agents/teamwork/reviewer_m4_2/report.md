# Milestone 4 Quality and Adversarial Review Report

**Reviewer:** `reviewer_m4_2`  
**Roles:** Reviewer, Critic  
**Date:** 2026-10-02  
**Target:** Milestone 4 (Packaging & Windows Standalone Executable)  
**Verdict:** **APPROVE**  

---

## 1. Executive Summary

Milestone 4 delivers the complete packaging and standalone runtime launcher architecture for the Supermarket Automation Software (SAS) desktop application on Windows. The implementation consists of:
1. `com.supermarket.Launcher`: Decoupled main bootstrap class with headless CLI support (`--help`, `--version`, `--smoke-test`, `-h`, `-v`, `-s`).
2. Maven Shade Plugin configuration (`pom.xml`): Builds a fully self-contained standalone fat JAR (`supermarket-automation-1.0.0-shaded.jar`, ~23.2 MB) containing JavaFX 21 runtime binaries, SQLite JDBC driver with native Windows x86_64 DLLs (`sqlitejdbc.dll`), Flyway-style SQL migrations (`V1`, `V2`), FXML layouts, and stylesheets.
3. Windows Batch Launcher (`run-sas.bat`): Robust launcher script featuring multi-tier JDK discovery (local toolchain, `JAVA_HOME`, standard Program Files locations, and system `PATH`), strict Java 21+ semantic version checking, automatic missing-JAR self-healing via Maven wrapper, verbatim `%*` argument forwarding, exit code propagation, and smart pause handling on fatal failures.

All empirical tests, edge-case stress tests, missing-artifact auto-recovery drills, and the full automated test suite (194 tests) completed with a 100% pass rate and zero integrity violations.

---

## 2. Integrity and Anti-Cheat Verification

As required by the adversarial critic mandate, the implementation was audited for integrity violations:
- **Hardcoded outputs or mock facades:** None. The smoke test and CLI modes in `Launcher.java` instantiate real SQLite database connections, execute actual SQL schema migrations (`schema_version` V2), query real seed items (27 items), invoke real `BillingService` and `InventoryService` domain logic, execute real atomic checkout transactions, and format actual thermal receipts using `ReceiptFormatter`.
- **Shortcuts or task bypass:** None. Standalone packaging creates genuine shaded uber-JARs with manifest `Main-Class: com.supermarket.Launcher`.
- **Fabricated verification artifacts:** None. Every command was empirically executed, logs were independently captured, and artifacts were directly verified on disk.
- **Integrity Assessment:** **PASSED (CLEAN)**.

---

## 3. Review Findings & Verification Matrix

### Verification Item 1: `run-sas.bat` JDK Discovery & Architecture
- **Criteria:** Verify discovery across `JAVA_HOME`, local toolchain (`C:\Users\User\.tools\jdk-21.0.6+7`), standard installation paths, and `PATH`.
- **Observation:** `run-sas.bat` implements hierarchical fallback:
  1. If `JAVA_HOME` is not defined, checks `C:\Users\User\.tools\jdk-21.0.6+7` and sets `JAVA_HOME`.
  2. If still undefined, checks Program Files (Oracle, Eclipse Adoptium, Liberica, Microsoft).
  3. If defined, resolves `%JAVA_HOME%\bin\java.exe`.
  4. If still undefined, queries `where java` in system `PATH`.
  5. If no Java executable is found, outputs actionable error, executes `pause` to preserve user visibility, and exits with code 1.
- **Status:** **PASS**

### Verification Item 2: Java 21+ Version Checking
- **Criteria:** Verify that Java 21+ requirement is strictly enforced and exits on obsolete versions.
- **Observation:** Executes `"%JAVA_EXE%" --version 2>nul`, extracts the version token, strips major version number (`delims=.-`), and compares `%%M geq 21`. If version check fails, displays clear diagnostic message, pauses, and exits with code 1.
- **Status:** **PASS**

### Verification Item 3: Missing-JAR Auto-Build Recovery
- **Criteria:** Verify self-healing behavior if the shaded fat JAR is missing.
- **Observation & Empirical Test:** 
  1. Simulating missing JAR by renaming `target\supermarket-automation-1.0.0-shaded.jar`.
  2. Running `run-sas.bat --version`.
  3. The script detected the missing JAR, logged:
     ```
     [SAS] Standalone JAR not found at: ...
     [SAS] Building standalone executable fat JAR via Maven wrapper...
     ```
     and invoked `call "%SCRIPT_DIR%mvnw.cmd" package -DskipTests`.
  4. Successfully rebuilt `target\supermarket-automation-1.0.0-shaded.jar` (23,235,895 bytes) and launched the application cleanly with exit code 0.
- **Status:** **PASS**

### Verification Item 4: Argument Forwarding & Error Exit Code Propagation
- **Criteria:** Verify argument forwarding (`%*`) and correct exit code return.
- **Observation & Empirical Test:**
  - Argument forwarding passed `%*` to `java -jar "%JAR_PATH%" %*`.
  - Tested `run-sas.bat --invalid-option`: Application displayed unknown option error, usage help, and exited with exit code 1 (`%APP_EXIT_CODE%` = 1).
  - Smart pause behavior: `if "%~1"=="" ( pause )` ensures CLI invocations return immediately with non-zero exit code, while double-click GUI runs pause before closing the console on unexpected crashes.
- **Status:** **PASS**

### Verification Item 5: Standalone CLI Execution (`--version`, `--help`, `--smoke-test`)
- **Empirical Execution Results:**
  1. `run-sas.bat --version`: Exited with code 0. Displayed application name, v1.0.0, Main-Class, Java Runtime 21.0.6, Java VM, OS, and default DB path.
  2. `run-sas.bat --help`: Exited with code 0. Displayed usage syntax and available CLI options.
  3. `run-sas.bat --smoke-test`: Exited with code 0. Executed all 5 stages:
     - SQLite JDBC Driver loading: OK (`org.sqlite.JDBC`)
     - SQLite Database & Migration Runner: OK (`Schema V2 applied successfully`)
     - Product Catalog queries: OK (`27 catalog items loaded`)
     - Core Business Services: OK (`Cart computed subtotal: $7.98`)
     - Checkout & Monospace Thermal Receipt Formatter: OK (`Receipt SAS-20261003-00001 formatted`)
     - Result: `SMOKE TEST RESULT: PASSED (All systems operational)`
- **Status:** **PASS**

### Verification Item 6: Full Automated Test Suite Execution
- **Command:** `.\mvnw.cmd test`
- **Output:**
  ```
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  ```
- **Total Tests:** 194 passing across DAOs, Persistence, Business Services, Receipt Formatter, JavaFX UI Controllers, Adversarial Tests, and `LauncherCliTest`.
- **Status:** **PASS**

---

## 4. Adversarial Challenges and Stress-Test Analysis

### Challenge 1: Path with Spaces and Special Characters
- **Attack scenario:** Launching from a directory containing spaces or passing arguments with spaces.
- **Investigation:** `run-sas.bat` quotes `%SCRIPT_DIR%`, `"%JAR_PATH%"`, `"%JAVA_EXE%"`, and forwards arguments using `%*`.
- **Verdict:** Robust.

### Challenge 2: Headless CI / Automated Scripting Compatibility
- **Attack scenario:** Scripts invoking `run-sas.bat` hung if an error occurred due to unconditional `pause`.
- **Investigation:** `run-sas.bat` checks `if "%~1"=="" ( pause )`. When invoked with arguments in automated testing or CI pipelines, it never pauses on error, immediately propagating the non-zero exit code via `exit /b %APP_EXIT_CODE%`.
- **Verdict:** Well-designed and safe for automation.

### Challenge 3: Shaded JAR Resource Conflicts
- **Attack scenario:** Overlapping `module-info.class` or signature files causing security or classloading failures.
- **Investigation:** `pom.xml` shade plugin excludes `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`, and includes `ServicesResourceTransformer` for `java.sql.Driver` service provider configuration. Inspection of the shaded JAR confirmed `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` and all resources are intact.
- **Verdict:** Robust.

---

## 5. Review Conclusion

Milestone 4 satisfies all operational, functional, and architectural requirements. The batch launcher `run-sas.bat`, the standalone shaded JAR packaging, and headless CLI verification capabilities are fully verified and production-ready.
