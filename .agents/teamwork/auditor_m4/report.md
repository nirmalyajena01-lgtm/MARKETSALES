# Forensic Integrity Audit Report: Milestone 4 (Packaging & Windows Standalone Executable)

**Auditor:** `auditor_m4`  
**Date:** 2026-10-02  
**Target:** Milestone 4 Deliverables (Supermarket Automation Software)  
**Profile:** General Project  
**Integrity Mode:** Development (per `ORIGINAL_REQUEST.md`)  
**Verdict:** **CLEAN**

---

## 1. Executive Summary

Milestone 4 deliverables have been subjected to an exhaustive forensic audit. Every claim made by `worker_m4` was empirically verified through clean rebuilds, binary decompilation/inspection, standalone JVM execution, batch script execution, test suite runs, and edge-case stress testing.

All forensic checks passed without exception:
- The shaded fat JAR (`target/supermarket-automation-1.0.0-shaded.jar`, ~23.2 MB) is a genuine uber-JAR containing full application bytecode, OpenJFX 21 controls/graphics/FXML runtime, SQLite JDBC engine, embedded native Windows DLLs (including `sqlitejdbc.dll` and JavaFX DirectX/Prism/Glass DLLs), database migration scripts (`V1__init_schema.sql`, `V2__seed_initial_catalog.sql`), FXML views, and CSS stylesheets.
- The `Launcher.java` entry point cleanly decouples JavaFX initialization from the standalone bootstrap, preventing modularity runtime errors, while providing a headless CLI engine. The `--smoke-test` command executes genuine SQLite in-memory database migrations, catalog queries, cart calculations, atomic checkout, and receipt formatting.
- `run-sas.bat` contains authentic Windows batch logic with runtime discovery for Java 21+, self-healing automatic packaging when the fat JAR is absent, and clean argument/exit-code passthrough.
- The entire project test suite passes cleanly: 194 tests run, 0 failures, 0 errors, 0 skipped.

---

## 2. Phase-by-Phase Forensic Analysis

### Phase 1: Source Code & Implementation Authenticity

| Forensic Check | Result | Evidence & Findings |
|---|---|---|
| **Hardcoded Test Results** | **PASS** | Inspection of `Launcher.java` and `LauncherCliTest.java` verified that outputs are dynamically computed. The smoke test executes real SQLite transactions, migrations, and calculations. No hardcoded results or synthetic PASS markers detected. |
| **Facade Implementations** | **PASS** | All classes in `com.supermarket.*` are fully implemented with substantive business logic, error handling, and parameter validation. No dummy stub returns (`return null`, `return true`) or mocked methods. |
| **Pre-populated Artifact Detection** | **PASS** | File system search revealed no pre-existing fake log files, test certificates, or synthetic verification artifacts prior to auditor testing. |
| **Dependency Delegation Audit** | **PASS** | Standard open-source libraries (`org.xerial:sqlite-jdbc`, `org.openjfx`, `org.slf4j`) are utilized strictly for platform infrastructure (JDBC driver, GUI toolkit, logging API). All supermarket domain logic, billing, inventory, price tracking, and analytics are custom-built Java 21 implementations. |

### Phase 2: Binary & Packaging Forensic Inspection

#### 1. Shaded Fat JAR Artifact Inspection
- **Artifact Path:** `target/supermarket-automation-1.0.0-shaded.jar`
- **File Size:** 23,235,895 bytes (~23.2 MB)
- **Manifest (`META-INF/MANIFEST.MF`):**
  ```manifest
  Manifest-Version: 1.0
  Created-By: Maven JAR Plugin 3.4.1
  Build-Jdk-Spec: 21
  Main-Class: com.supermarket.Launcher
  ```
- **Service Providers (`META-INF/services/java.sql.Driver`):**
  - Contains `org.sqlite.JDBC` (properly merged via `ServicesResourceTransformer`).
- **Native Binaries:**
  - `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` (969,728 bytes)
  - `prism_d3d.dll` (179,712 bytes), `glass.dll` (254,464 bytes), `decora_sse.dll` (81,408 bytes)
- **Embedded Database Migrations:**
  - `db/migrations/V1__init_schema.sql` (schema definitions)
  - `db/migrations/V2__seed_initial_catalog.sql` (seed catalog items)
- **UI Layouts & Styling:**
  - All 7 FXML views present (`main.fxml`, `pos.fxml`, `inventory.fxml`, `restock.fxml`, `price.fxml`, `analytics.fxml`, `receipt_preview.fxml`)
  - Modern enterprise stylesheet: `css/style.css`

#### 2. Clean Build Reproducibility
- Command executed by auditor: `.\mvnw.cmd clean package -DskipTests`
- Build completed successfully in 16.114 s with exit code `0`.
- Fat JAR was regenerated with complete file tree and identical byte layout.

### Phase 3: Standalone Execution & CLI Verification

#### 1. Standalone JVM Execution (`java -jar`)
- `--version` / `-v`:
  ```
  Supermarket Automation Software (SAS) v1.0.0
  Main-Class       : com.supermarket.Launcher
  Java Runtime     : 21.0.6 (Microsoft)
  Java VM          : OpenJDK 64-Bit Server VM (21.0.6+7-LTS)
  Operating System : Windows 11 10.0 (amd64)
  Default DB Path  : C:\Users\User\.supermarket_automation\supermarket.db
  ```
  Exit code: `0` (PASS)

- `--help` / `-h`:
  Displays complete CLI usage guide and options. Exit code: `0` (PASS)

- `--smoke-test` / `-s`:
  Executes full 5-stage verification:
  1. `[1/5] Testing SQLite JDBC Driver loading... OK (org.sqlite.JDBC loaded)`
  2. `[2/5] Testing SQLite Database & Migration Runner... OK (Schema V2 applied successfully)`
  3. `[3/5] Testing Product Catalog queries... OK (27 catalog items loaded)`
  4. `[4/5] Testing Core Business Services (Billing & Inventory)... OK (Cart computed subtotal: $7.98)`
  5. `[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... OK (Receipt SAS-20261003-00001 formatted)`
  Result: `SMOKE TEST RESULT: PASSED (All systems operational)`, Exit code: `0` (PASS)

- Unknown argument (`--unknown-flag`):
  Prints error message, displays help, exits with code `1` (PASS)

#### 2. Windows Batch Launcher (`run-sas.bat`)
- Tested commands:
  - `run-sas.bat --version` -> Exit code `0` (PASS)
  - `run-sas.bat --smoke-test` -> Exit code `0` (PASS)
  - `run-sas.bat -h` -> Exit code `0` (PASS)
  - `run-sas.bat --unrecognized-flag` -> Returns exit code `1` with formatted notice (PASS)

#### 3. Self-Healing Automatic Build Recovery
- Auditor renamed `target/supermarket-automation-1.0.0-shaded.jar` to simulate a freshly cloned or cleaned workspace.
- Executed `run-sas.bat --version`.
- Script detected absent artifact:
  `[SAS] Standalone JAR not found at: ...`
  `[SAS] Building standalone executable fat JAR via Maven wrapper...`
- Automatically triggered `.\mvnw.cmd package -DskipTests`, compiled and assembled shaded jar, then launched `--version` successfully with exit code `0`.

### Phase 4: Automated Test Suite Execution

- Command executed by auditor: `.\mvnw.cmd test`
- Results:
  - **Tests run:** 194
  - **Failures:** 0
  - **Errors:** 0
  - **Skipped:** 0
  - **Build Status:** `BUILD SUCCESS` (Total time: 28.726 s)
- `LauncherCliTest`: 9 test cases verified covering CLI argument parsing, flags (`--help`, `-h`, `--version`, `-v`, `--smoke-test`, `-s`), invalid option rejection, and empty argument GUI routing.

---

## 3. Adversarial Review & Stress Testing

1. **Isolation of Smoke Test DB:**
   - *Attack hypothesis:* CLI smoke test may overwrite or pollute the user's primary database at `~/.supermarket_automation/supermarket.db`.
   - *Verification:* Inspected `Launcher.runSmokeTest()`. It creates an in-memory SQLite database instance (`DatabaseManager.createInMemory()`), which is destroyed on process exit. The persistent database is never modified.
2. **Double Execution / State Leak:**
   - Repeated smoke tests in succession produce consistent results with no leaked file handles or locked resources.
3. **Invalid CLI Flags:**
   - Unrecognized flags trigger exit code 1 with stderr warning and help text rather than launching the JavaFX GUI unexpectedly.
4. **JavaFX Modularity Bypass:**
   - JavaFX 21 requires modular runtime handling when launching from a class extending `Application`. Decoupling `Launcher` from `Application` allows direct `java -jar` execution on standard Windows OpenJDK 21 installations without requiring `--module-path` or `--add-modules` arguments.

---

## 4. Final Audit Verdict

**CLEAN** — Milestone 4 fulfills all technical, packaging, execution, and integrity criteria. No violations, mocks, facades, or fabrications were found.
