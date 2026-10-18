# Milestone 4 Execution Report: Packaging & Windows Standalone Executable

**Author:** `worker_m4`  
**Milestone:** Milestone 4 (Packaging & Windows Standalone Executable)  
**Parent Agent:** `orchestrator_2` (`b1867780-0866-4bc6-a559-811ca0300e3f`)  
**Date:** 2026-10-02  
**Status:** COMPLETE (100% Pass Rate, 194 Automated Tests)

---

## 1. Executive Summary

Milestone 4 successfully packages the Supermarket Automation Software (SAS) desktop application into an independent, production-grade standalone executable fat JAR (`target/supermarket-automation-1.0.0-shaded.jar`) and provides an enterprise Windows launcher batch script (`run-sas.bat`).

All functional, packaging, and verification requirements from the dispatch have been fulfilled:
- Shaded fat JAR packaging configured with `maven-shade-plugin 3.5.2` including `ManifestResourceTransformer` (`Main-Class: com.supermarket.Launcher`) and `ServicesResourceTransformer` for SPI merging.
- Complete bundling of JavaFX 21.0.4 runtime components, SQLite JDBC 3.45.2.0 driver with native Windows x86_64 binaries, Flyway-style SQL migrations (`V1__init_schema.sql`, `V2__seed_initial_catalog.sql`), FXML views, and enterprise CSS.
- Enhanced `Launcher.java` with headless CLI flags (`--smoke-test`, `--version`, `--help`) enabling standalone smoke verification on Windows without requiring a physical monitor or display session.
- Robust Windows launch script `run-sas.bat` featuring automatic Java 21+ runtime discovery, automatic build-if-missing recovery, clean parameter pass-through (`%*`), and graceful error handling.
- Full automated test suite expanded to 194 passing tests (9 new automated tests in `LauncherCliTest.java`), with zero regressions and 100% pass rate.

---

## 2. Packaging Configuration & Artifact Inspection

### 2.1 Maven Shade Configuration (`pom.xml`)
The `maven-shade-plugin` configuration was updated to include `ServicesResourceTransformer` alongside `ManifestResourceTransformer`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>${maven-shade-plugin.version}</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <shadedArtifactAttached>true</shadedArtifactAttached>
                <shadedClassifierName>shaded</shadedClassifierName>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.supermarket.Launcher</mainClass>
                    </transformer>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 2.2 Shaded Executable Fat JAR Metrics
- **Artifact Path:** `target/supermarket-automation-1.0.0-shaded.jar`
- **File Size:** 23,235,532 bytes (~23.2 MB)
- **Manifest File (`META-INF/MANIFEST.MF`):**
  ```manifest
  Manifest-Version: 1.0
  Created-By: Maven JAR Plugin 3.4.1
  Build-Jdk-Spec: 21
  Main-Class: com.supermarket.Launcher
  ```

### 2.3 Verified Shaded Components in Fat JAR
Inspection using JDK 21 `jar` verified the presence of all essential dependencies and resources:
1. **JavaFX Runtime Controls & Graphics:**
   - `javafx.controls` (`com/sun/javafx/scene/control/`, `javafx/scene/control/TableView.class`, etc.)
   - `javafx.fxml` (`javafx/fxml/FXMLLoader.class`, `javafx/fxml/Initializable.class`, etc.)
   - `javafx.graphics` (`javafx/stage/Stage.class`, `javafx/scene/Scene.class`, Prism hardware/software pipelines)
   - `javafx.base` (`javafx/beans/property/`, `javafx/collections/ObservableList.class`, etc.)
2. **SQLite JDBC Persistence:**
   - Java Driver: `org/sqlite/JDBC.class`, `META-INF/services/java.sql.Driver`
   - Native Windows Library: `org/sqlite/native/Windows/x86_64/sqlitejdbc.dll`
   - Native Fallback Binaries: `x86`, `aarch64`, `armv7`
3. **Database Migrations:**
   - `db/migrations/V1__init_schema.sql` (Tables: items, sales_transactions, receipt_items, restock_audit_log, price_history_log)
   - `db/migrations/V2__seed_initial_catalog.sql` (Initial catalog items across produce, dairy, bakery, beverages)
4. **FXML Views & Styles:**
   - `fxml/main.fxml`, `fxml/pos.fxml`, `fxml/inventory.fxml`, `fxml/restock.fxml`, `fxml/price.fxml`, `fxml/analytics.fxml`, `fxml/receipt_preview.fxml`
   - `css/style.css`

---

## 3. Launcher CLI & Headless Smoke Test Architecture

### 3.1 Design of `Launcher.java`
`Launcher.java` provides a dual-mode entry point:
- **GUI Mode (Default / No Arguments):** Delegates directly to `SupermarketApp.main(args)` to launch the interactive JavaFX desktop application.
- **Headless CLI Mode (`--smoke-test`, `--version`, `--help`):** Processes command-line switches in headless mode without initializing the JavaFX display pipeline, allowing automated operational verification in server, CI, or headless Windows environments.

### 3.2 Smoke Test Pipeline (`--smoke-test`)
The smoke test executes a comprehensive 5-step operational pipeline:
1. `[1/5] Testing SQLite JDBC Driver loading`: Verifies `org.sqlite.JDBC` is present on the runtime classpath.
2. `[2/5] Testing SQLite Database & Migration Runner`: Creates an isolated SQLite database, applies migrations V1 & V2, and verifies `schema_version >= 2`.
3. `[3/5] Testing Product Catalog queries`: Loads catalog items via `SqliteItemRepository` and verifies seed records exist (27 items).
4. `[4/5] Testing Core Business Services (Billing & Inventory)`: Instantiates `BillingService`, `InventoryService`, `PriceService`, and `AnalyticsService`; creates a cart, adds items, and verifies subtotal calculation.
5. `[5/5] Testing Checkout & Monospace Thermal Receipt Formatter`: Performs atomic checkout with stock decrement and transaction recording, then formats a 40-column monospace thermal receipt via `ReceiptFormatter`.

---

## 4. Windows Standalone Launcher (`run-sas.bat`)

The batch script `run-sas.bat` in the project root provides a seamless launch experience on Windows:
- **Intelligent Java 21+ Discovery:**
  - Evaluates `JAVA_HOME` if defined and checks version.
  - Checks the local toolchain directory `C:\Users\User\.tools\jdk-21.0.6+7`.
  - Checks standard Windows installation paths (`%ProgramFiles%\Java\jdk-21`, `%ProgramFiles%\Eclipse Adoptium\jdk-21`, etc.).
  - Evaluates `where java` on system `PATH`.
  - Parses `java --version` to ensure major version is at least 21 (rejecting older versions such as Java 8).
- **Self-Healing Build Mechanism:**
  - Checks if `target\supermarket-automation-1.0.0-shaded.jar` exists.
  - If missing, automatically invokes `mvnw.cmd package -DskipTests` to compile and package the fat JAR before launching.
- **Console Feedback & Parameter Forwarding:**
  - Forwards all command-line parameters (`%*`) to the underlying JVM.
  - Pauses on fatal errors or when double-clicked interactively with no parameters to keep error messages visible.

---

## 5. Empirical Verification Evidence

### 5.1 Verification: `run-sas.bat --version`
```cmd
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation>run-sas.bat --version
[SAS] Initializing Supermarket Automation Software Launcher...
[SAS] Java Runtime: "C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe"
[SAS] Launching Supermarket Automation Software...
[SAS] Target JAR: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"

Supermarket Automation Software (SAS) v1.0.0
Main-Class       : com.supermarket.Launcher
Java Runtime     : 21.0.6 (Microsoft)
Java VM          : OpenJDK 64-Bit Server VM (21.0.6+7-LTS)
Operating System : Windows 11 10.0 (amd64)
Default DB Path  : C:\Users\User\.supermarket_automation\supermarket.db
```
**Exit Code:** `0`

### 5.2 Verification: `run-sas.bat --help`
```cmd
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation>run-sas.bat --help
[SAS] Initializing Supermarket Automation Software Launcher...
[SAS] Java Runtime: "C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe"
[SAS] Launching Supermarket Automation Software...
[SAS] Target JAR: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"

Supermarket Automation Software (SAS) v1.0.0
Usage: java -jar supermarket-automation-1.0.0-shaded.jar [OPTIONS]

Options:
  --smoke-test, -s    Run automated headless smoke test and database verification
  --version, -v       Display application version and runtime metadata
  --help, -h          Display this help message and command-line options

If no options are specified, the JavaFX desktop graphical user interface will launch.
```
**Exit Code:** `0`

### 5.3 Verification: `run-sas.bat --smoke-test`
```cmd
C:\Users\User\.gemini\antigravity\scratch\supermarket-automation>run-sas.bat --smoke-test
[SAS] Initializing Supermarket Automation Software Launcher...
[SAS] Java Runtime: "C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe"
[SAS] Launching Supermarket Automation Software...
[SAS] Target JAR: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"

==============================================================
Supermarket Automation Software (SAS) - Standalone Smoke Test
==============================================================
Application Version : 1.0.0
Java Runtime        : 21.0.6 (Microsoft)
Java Home           : C:\Users\User\.tools\jdk-21.0.6+7
Operating System    : Windows 11 10.0 (amd64)
Default DB Path     : C:\Users\User\.supermarket_automation\supermarket.db
--------------------------------------------------------------
[1/5] Testing SQLite JDBC Driver loading... OK (org.sqlite.JDBC loaded)
[2/5] Testing SQLite Database & Migration Runner... Oct 03, 2026 2:56:43 AM com.supermarket.persistence.MigrationRunner migrate
INFO: Current database schema version: 0
Oct 03, 2026 2:56:43 AM com.supermarket.persistence.MigrationRunner migrate
INFO: Applying migration V1: Initial Schema
Oct 03, 2026 2:56:43 AM com.supermarket.persistence.MigrationRunner migrate
INFO: Applying migration V2: Catalog Seed Data
Oct 03, 2026 2:56:43 AM com.supermarket.persistence.MigrationRunner migrate
INFO: Database migrations applied successfully.
OK (Schema V2 applied successfully)
[3/5] Testing Product Catalog queries... OK (27 catalog items loaded)
[4/5] Testing Core Business Services (Billing & Inventory)... OK (Cart computed subtotal: $7.98)
[5/5] Testing Checkout & Monospace Thermal Receipt Formatter... OK (Receipt SAS-20261003-00001 formatted)
--------------------------------------------------------------
SMOKE TEST RESULT: PASSED (All systems operational)
==============================================================
```
**Exit Code:** `0`

### 5.4 Verification: Missing JAR Self-Healing Test
The standalone JAR was temporarily moved away. Calling `run-sas.bat` triggered the automatic build and successfully launched:
```cmd
[SAS] Initializing Supermarket Automation Software Launcher...
[SAS] Java Runtime: "C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe"
[SAS] Standalone JAR not found at: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"
[SAS] Building standalone executable fat JAR via Maven wrapper...
[INFO] Scanning for projects...
[INFO] Building Supermarket Automation Software (SAS) 1.0.0
...
[INFO] BUILD SUCCESS
[SAS] Launching Supermarket Automation Software...
[SAS] Target JAR: "C:\Users\User\.gemini\antigravity\scratch\supermarket-automation\target\supermarket-automation-1.0.0-shaded.jar"

Supermarket Automation Software (SAS) v1.0.0
Main-Class       : com.supermarket.Launcher
Java Runtime     : 21.0.6 (Microsoft)
...
```
**Exit Code:** `0`

### 5.5 Verification: Direct JAR Execution
```powershell
C:\Users\User\.tools\jdk-21.0.6+7\bin\java.exe -jar target\supermarket-automation-1.0.0-shaded.jar --smoke-test
```
**Result:** PASSED, exit code `0`.

### 5.6 Automated Test Suite Execution
```cmd
.\mvnw.cmd test
```
**Result Summary:**
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
...
[INFO] Results:
[INFO] 
[INFO] Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  27.554 s
```

---

## 6. Deliverable Artifacts

| Artifact | Location | Purpose |
|---|---|---|
| Shaded Fat JAR | `target/supermarket-automation-1.0.0-shaded.jar` | Production executable with all dependencies shaded |
| Windows Launcher Script | `run-sas.bat` | Production batch script for launching application on Windows |
| Main Class Launcher | `src/main/java/com/supermarket/Launcher.java` | Entry point decoupling JavaFX bootstrap with CLI smoke test |
| CLI Unit Tests | `src/test/java/com/supermarket/LauncherCliTest.java` | 9 automated unit tests verifying CLI flags and smoke test |
| Maven Configuration | `pom.xml` | Updated shade plugin with `ServicesResourceTransformer` |
| Handoff Report | `.agents/teamwork/worker_m4/handoff.md` | Formal 5-component handoff for Milestone 4 |
| Milestone 4 Report | `.agents/teamwork/worker_m4/report.md` | Detailed execution report |

---

## 7. Conclusion

Milestone 4 is complete and fully verified. The standalone shaded fat JAR and Windows batch launcher are production-ready, fully self-contained, and pass all automated and empirical verification criteria.
