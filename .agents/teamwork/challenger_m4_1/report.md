# Milestone 4 Adversarial Challenge Report

**Agent:** `challenger_m4_1` (EMPIRICAL CHALLENGER: critic, specialist)  
**Parent Agent:** `orchestrator_2` (`b1867780-0866-4bc6-a559-811ca0300e3f`)  
**Target:** Milestone 4 (Packaging & Windows Standalone Executable)  
**Date:** 2026-10-02  
**Overall Risk Assessment:** LOW (Packaging and CLI are robust; two peripheral findings identified for M5 hardening)

---

## 1. Executive Summary & Verdict

Milestone 4 delivers a standalone shaded fat JAR (`target\supermarket-automation-1.0.0-shaded.jar`, ~23.2 MB) and a Windows batch launcher script (`run-sas.bat`).

All four core mission objectives were independently and empirically verified:
1. **CLI Argument Parser Edge Cases**: Thoroughly tested across single flags, uppercase/mixed-case variants, flag combinations, boundary values, and malformed inputs. All unknown flags reliably return exit code `1` with standard error diagnostics; valid options (`--smoke-test`, `--version`, `--help`) reliably return exit code `0`.
2. **Standalone Fat JAR Execution**: Shaded JAR runs independently via `java -jar target\supermarket-automation-1.0.0-shaded.jar` without requiring JavaFX runtime module configuration. All embedded native dependencies (`sqlitejdbc.dll`), migrations (`V1`, `V2`), views (`fxml/*`), and styles (`css/style.css`) are present and intact.
3. **Smoke Test Realism & Unmocked Engine**: Verified that `Launcher.runSmokeTest()` executes against a real unmocked SQLite persistence engine, applies schema migrations V1 and V2, queries 27 catalog items, performs atomic inventory deduction and checkout, and generates a formatted 40-column ESC/POS monospace thermal receipt.
4. **Full Automated Test Suite**: Expanded the automated test suite with 42 new adversarial stress tests in `LauncherAdversarialCliTest.java`. The entire test suite passed at 100% (236 tests run, 0 failures, 0 errors, 0 skipped).

**Final Milestone 4 Verdict:** **APPROVE**.

---

## 2. Empirical Verification Matrix

| # | Test Dimension | Input / Command | Expected Behavior | Actual Behavior | Result |
|---|----------------|-----------------|-------------------|-----------------|--------|
| 1 | Standard Smoke Test | `java -jar <jar> --smoke-test` | Runs 5 stages, exits 0 | All 5 stages pass, exits 0 | **PASS** |
| 2 | Short Smoke Test | `java -jar <jar> -s` | Runs 5 stages, exits 0 | All 5 stages pass, exits 0 | **PASS** |
| 3 | Standard Version Flag | `java -jar <jar> --version` | Prints version & JVM metadata, exits 0 | Prints v1.0.0, Java 21, exits 0 | **PASS** |
| 4 | Short Version Flag | `java -jar <jar> -v` | Prints version & JVM metadata, exits 0 | Prints v1.0.0, exits 0 | **PASS** |
| 5 | Standard Help Flag | `java -jar <jar> --help` | Prints CLI usage options, exits 0 | Prints options, exits 0 | **PASS** |
| 6 | Short Help Flag | `java -jar <jar> -h` | Prints CLI usage options, exits 0 | Prints options, exits 0 | **PASS** |
| 7 | Alternative Help Flag | `java -jar <jar> "-?"` | Prints CLI usage options, exits 0 | Prints options, exits 0 | **PASS** |
| 8 | Uppercase Version | `java -jar <jar> --VERSION` | Case-insensitive match, exits 0 | Prints version, exits 0 | **PASS** |
| 9 | Mixed-Case Smoke Test | `java -jar <jar> --Smoke-Test` | Case-insensitive match, exits 0 | Runs 5 stages, exits 0 | **PASS** |
| 10 | Uppercase Short Flag | `java -jar <jar> -S`, `-V`, `-H` | Case-insensitive match, exits 0 | All exit 0 | **PASS** |
| 11 | Multi-Arg Combination | `java -jar <jar> --version --help` | First flag executed, exits 0 | Version printed, exits 0 | **PASS** |
| 12 | Multi-Arg Combination | `java -jar <jar> --help --version` | First flag executed, exits 0 | Help printed, exits 0 | **PASS** |
| 13 | Unknown Flag Precedence | `java -jar <jar> --invalid-flag --smoke-test` | First flag rejected, exits 1 | Stderr error, exits 1 | **PASS** |
| 14 | Unknown Flag Long | `java -jar <jar> --invalid-flag` | Rejects with error, exits 1 | Stderr error, exits 1 | **PASS** |
| 15 | Unknown Flag Short | `java -jar <jar> -x` | Rejects with error, exits 1 | Stderr error, exits 1 | **PASS** |
| 16 | Malformed Hyphen Flags | `java -jar <jar> -`, `--`, `---unknown` | Rejects with error, exits 1 | Stderr error, exits 1 | **PASS** |
| 17 | Unknown Assignment Flag | `java -jar <jar> --help=true` | Rejects with error, exits 1 | Stderr error, exits 1 | **PASS** |
| 18 | Batch Launcher Version | `run-sas.bat --version` | Discovers Java 21, exits 0 | Version displayed, exit code 0 | **PASS** |
| 19 | Batch Launcher Smoke Test | `run-sas.bat --smoke-test` | Executes 5-stage check, exits 0 | All stages pass, exit code 0 | **PASS** |
| 20 | Batch Launcher Unknown Flag | `run-sas.bat --invalid-flag` | Propagates exit code 1 | Displays error, exit code 1 | **PASS** |
| 21 | Batch Launcher Short Unknown | `run-sas.bat -x` | Propagates exit code 1 | Displays error, exit code 1 | **PASS** |
| 22 | Unmocked SQLite Persistence | In-memory DB migration & queries | Executes real C library DLL & SQL | Verified via `sqlitejdbc.dll` | **PASS** |
| 23 | Thermal Receipt Realism | Format real checkout receipt | Monospace 40-col text with serial | Serial `SAS-20261003-00001` match | **PASS** |
| 24 | Full Regression Suite | `.\mvnw.cmd test` | 100% passing tests | 236 tests run, 0 failures, 0 err | **PASS** |

---

## 3. Deep Dive: Unmocked Persistence & Business Logic Verification

`Launcher.runSmokeTest()` was inspected line-by-line and verified empirically:

1. **Stage 1 (SQLite JDBC Driver Loading)**:
   - Loads class `org.sqlite.JDBC` via `Class.forName()`.
   - Native Windows binary `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll` inside the shaded JAR is extracted by SQLite JDBC to load the underlying SQLite 3.x engine.
2. **Stage 2 (Database Connection & Migration Runner)**:
   - Instantiates `DatabaseManager.createInMemory()`, configuring `jdbc:sqlite:file:<memname>?mode=memory&cache=shared` with `PRAGMA foreign_keys = ON;`.
   - Executes `MigrationRunner.migrate(db)` reading physical resource files `db/migrations/V1__init_schema.sql` and `db/migrations/V2__seed_initial_catalog.sql`.
   - Queries `schema_version` table via `SELECT MAX(version) FROM schema_version` and validates schema version is at least 2.
3. **Stage 3 (Product Catalog Query)**:
   - Instantiates `SqliteItemRepository(db)` and executes `findAll()`.
   - Asserts that all 27 seeded items from migration V2 are present in SQLite.
4. **Stage 4 (Headless Core Business Logic)**:
   - Creates a real active cart via `BillingService.createCart()`.
   - Adds 2 units of the first catalog item (`BEV-001`, Sparkling Mineral Water 1.5L, unit price $3.99).
   - Computes totals via `BillingService.computeTotals(cart)`, returning subtotal $7.98.
5. **Stage 5 (Atomic Checkout & Monospace Thermal Receipt)**:
   - Executes `BillingService.checkout(cart, $7.98, "SMOKE_TESTER")`.
   - Writes the sales transaction to SQLite `sales_transactions`, decrements inventory on `items`, and inserts `receipt_items`.
   - Formats the thermal receipt using `ReceiptFormatter`, verifying store headers, serial numbers, lines, and monospace alignment.
   - Validates that the default persistence folder (`C:\Users\User\.supermarket_automation`) is created and writable.

Zero mocking libraries (`Mockito`, mocks, stubs) are involved in this execution path.

---

## 4. Adversarial Findings & Flakiness Analysis (For Milestone 5 Hardening)

During stress-testing under heavy test suite execution, two specific risks were surfaced that should be addressed during Milestone 5 hardening:

### Finding 1 (Medium): Intermittent Race Condition / Flakiness in `PriceServiceTest.testUpdateSellingPrice`
- **Location**: `src/main/java/com/supermarket/repository/SqlitePriceHistoryRepository.java:73`
- **Symptom**: During full suite execution under CPU load, `PriceServiceTest.testUpdateSellingPrice` failed once with:
  ```
  expected: 1.99
   but was: 1.79
  ```
- **Root Cause**: `SqlitePriceHistoryRepository.findByItemCode()` uses:
  ```sql
  SELECT id, item_code, old_price, new_price, changed_by, timestamp FROM price_history_log WHERE item_code = ? ORDER BY timestamp DESC
  ```
  When two updates occur consecutively within the same system clock tick (`LocalDateTime.now()`), both rows have the exact same `timestamp` string in SQLite. Because SQLite does not enforce secondary ordering without an explicit tie-breaker, the result order between the two rows is non-deterministic.
- **Recommended Fix for M5**:
  Update query in `SqlitePriceHistoryRepository.java`:
  ```sql
  ORDER BY timestamp DESC, id DESC
  ```
  This guarantees strict, deterministic LIFO ordering even when timestamps are identical.

### Finding 2 (Low / Cosmetic): JavaFX PropertyValueFactory Reflection Warnings on Java Records
- **Location**: `InventoryController.java`, `PriceController.java`, `RestockController.java`, `AnalyticsController.java`
- **Symptom**: When the GUI is launched, JavaFX logs warnings:
  ```
  WARNING: Can not retrieve property 'code' in PropertyValueFactory...
  java.lang.IllegalStateException: Cannot read from unreadable property code
  ```
- **Root Cause**: The domain models (`Item`, `RestockAudit`, `SalesStatistic`) are Java records (`public record Item(...)`). JavaFX's `PropertyValueFactory` expects JavaBean getters (e.g. `getCode()`) or JavaFX `Property` accessors. For records, it attempts reflective field access, which fails or issues warnings in Java 21 modular environments.
- **Recommended Fix for M5**:
  Replace `PropertyValueFactory` for records with lambda cell value factories (which are already partially used in those controllers):
  ```java
  colCode.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().code()));
  ```
  Or add standard getter aliases to the record definitions (`public String getCode() { return code(); }`).

---

## 5. Automated Test Suite Expansion

To ensure ongoing regression protection for packaging and CLI behavior, `src/test/java/com/supermarket/LauncherAdversarialCliTest.java` was created with 42 new test methods covering:
1. Standard & case-insensitive flag variations (`--help`, `-h`, `"-?"`, `--HELP`, `--version`, `-v`, `--VERSION`, `--smoke-test`, `-s`, `--SMOKE-TEST`, `--Smoke-Test`, `-S`)
2. Unknown & malformed options (`--invalid-flag`, `-x`, `--foo`, `-`, `--`, `---unknown`, `-9`, `--help=true`, `--version=1.0`, etc.)
3. Multi-argument precedence & flag combinations
4. Null, empty array, empty quotes, and whitespace input boundaries
5. `isCliCommand` discrimination logic
6. Unmocked smoke test execution and output verification

Total project test suite status:
- **Total Tests Run**: 236
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0
- **Pass Rate**: 100%
- **Build Status**: SUCCESS
